package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorDescriptor.ConfigField;
import org.dromara.databus.connector.ConnectorDescriptor.OperationDef;
import org.dromara.databus.connector.ConnectorDescriptor.Option;
import org.dromara.databus.connector.ConnectorDescriptor.ShowWhen;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.sql.BatchResult;
import org.dromara.databus.connector.sql.ExecuteResult;
import org.dromara.databus.connector.sql.QueryOptions;
import org.dromara.databus.connector.sql.QueryResult;
import org.dromara.databus.connector.sql.SqlConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC 连接器公共实现（方案 §三 / §四 / §六 / §七）。
 *
 * <p><b>不是 Spring {@code @Component}</b>：连接器零内置（§1.2），由平台 {@code PluginManager} 反射
 * 实例化、单例缓存在 {@code ConnectorRegistry}。本类随各库包被打进 {@code lib/}，child-first 下
 * 每个包一份 Class 对象——因此 {@link #pools} 与驱动缓存天然按包隔离，卸载即彻底（§4.3）。
 *
 * <p>子类只声明自己对应哪个 {@link JdbcDbType}（十几行），type / 驱动类名 / URL 模板 / 默认端口 /
 * 探活语句全部来自枚举，用户无法自填 driverClass（§9.1 P0）。
 *
 * <p>两层契约都实现：能力接口 {@link SqlConnector} 的三个方法（脚本组件走这条，有编译期类型检查），
 * 以及通用逃生舱 {@link #invoke}（按 operation 分发到同一套实现，保证「不改平台也能调」）。
 *
 * <p><b>rowCount 的语义偏离</b>：§7.1 说 rowCount「不受 maxRows 截断影响」，实现上做不到——
 * {@code setMaxRows} 之后驱动只回传上限内的行，拿不到真实命中总数（要拿就得再跑一次
 * {@code count(*)}，等于把用户 SQL 执行两遍）。此处 rowCount = 实际读取行数，
 * 达到 maxRows 时应视为「结果被截断」而非「一共这么多行」。
 *
 * @author databus
 */
public abstract class AbstractJdbcConnector implements SqlConnector {

    private static final Logger log = LoggerFactory.getLogger(AbstractJdbcConnector.class);

    /** operation 名（§3.3，行业中立命名，不沿用 BPM rdsExecute 的 getString/getMap/... 八方法） */
    public static final String OPERATION_QUERY = "query";
    public static final String OPERATION_EXECUTE = "execute";
    public static final String OPERATION_BATCH = "batch";

    /** 组件注册名（§3.3 / §8.1：用户心智是「数据库」不是「JDBC」，故用 db* 前缀且不带协议名） */
    private static final String CMP_QUERY = "dbQuery";
    private static final String CMP_EXECUTE = "dbExecute";
    private static final String CMP_BATCH = "dbBatch";

    /** 连接器族（§8.3）：组件按族匹配，一个 dbQuery 服务所有 jdbc-* 库 */
    private static final String FAMILY = "jdbc";

    /** 图标（与平台 ProtocolJdbc 系组件同一枚，物料观感一致） */
    private static final String ICON = "ph:database";

    /** resultMode 合法取值（§3.3 归位关系：first←getMap、rows←getMaps、scalar←getString/getInt/...） */
    private static final List<String> RESULT_MODES = List.of("rows", "first", "scalar");

    /** 探活语句的查询超时（秒）：与 §3.5 的 3000ms 建连超时同量级，不留「连上了但探活挂死」的口子 */
    private static final int PROBE_QUERY_TIMEOUT_SEC = 3;

    /** 批量默认批大小 */
    private static final int DEFAULT_BATCH_SIZE = 100;

    /** logSql=true 时每个参数只回显的前缀长度（§7.3：args 可能含身份证等敏感数据） */
    private static final int ARG_PREVIEW_LEN = 8;

    /** 连接池注册表：每包一份，见类注释 */
    private final JdbcPoolRegistry pools = new JdbcPoolRegistry();

    /**
     * 本连接器对应的库种。子类唯一必须实现的方法。
     */
    public abstract JdbcDbType dbType();

    @Override
    public String getType() {
        return dbType().getType();
    }

    /**
     * 驱动类名来自 {@link JdbcDbType}（用户改不了，§9.1 P0），平台装载时据此校验驱动确实在包内。
     * 与 {@code JdbcDriverRegistry.driverFor(dbType())} 取的是同一个名字，两者不会走岔。
     */
    @Override
    public String requiredDriverClass() {
        return dbType().getDriverClass();
    }

    // ---------------------------- 元数据 ----------------------------

    /**
     * 声明 §3.2 全量 configSchema 与 §3.3 三个 operation。
     * 前端连接管理页表单与组件物料都由这份元数据动态渲染，平台侧不写死任何字段。
     */
    @Override
    public ConnectorDescriptor describe() {
        JdbcDbType type = dbType();
        ConnectorDescriptor descriptor = new ConnectorDescriptor();
        descriptor.setType(type.getType());
        descriptor.setLabel(type.getLabel());
        descriptor.setIcon(ICON);
        descriptor.setFamily(FAMILY);
        descriptor.setConfigSchema(configSchema(type));

        descriptor.getOperations().add(queryOperation());
        descriptor.getOperations().add(executeOperation());
        descriptor.getOperations().add(batchOperation());
        return descriptor;
    }

    /** §3.2 configSchema 全清单：基础 6 项 + 高级 12 项，前端按 group 分 tab */
    private Map<String, ConfigField> configSchema(JdbcDbType type) {
        Map<String, ConfigField> schema = new LinkedHashMap<>();

        // 模式开关：host/port/database 与 urlOverride 是两条互斥路径。不显式表达这个选择，
        // host 的 required=true 就会把 urlOverride 这条文档化的逃生舱直接拦死（前端照 schema 硬校验）。
        ConfigField urlMode = text("连接串来源", false, JdbcConnectionCfg.URL_MODE_BASIC, "基础", "SELECT", null,
            "「按主机拼装」填下方主机/端口/库名；「自定义 JDBC URL」改填高级项里的完整连接串");
        urlMode.setOptions(List.of(new Option("按主机拼装", JdbcConnectionCfg.URL_MODE_BASIC),
            new Option("自定义 JDBC URL", "override")));
        schema.put("urlMode", urlMode);

        // 必须用 ne（而非 eq basic）：本次改造前存的 config 里没有 urlMode 键，
        // eq 会把 String(null) != "basic" 判成不满足，三个字段凭空消失、老连接改不了。
        ShowWhen notOverride = new ShowWhen("urlMode", null, "override", null, null);
        ConfigField host = text("主机", true, null, "基础", "TEXT", null, "数据库主机名或 IP");
        host.setShowWhen(notOverride);
        schema.put("host", host);
        ConfigField port = number("端口", true, type.getDefaultPort(), "基础", null, null);
        port.setShowWhen(notOverride);
        schema.put("port", port);
        ConfigField database = text(type.getDatabaseLabel(), true, null, "基础", "TEXT", null,
            type == JdbcDbType.ORACLE ? "Oracle 填服务名（serviceName），不是 SID" : null);
        database.setShowWhen(notOverride);
        schema.put("database", database);
        schema.put("username", text("用户名", true, null, "基础", "TEXT", null,
            "建议使用只读数据库账号：连接开启「只读」只是弱防线，账号权限才是主防线"));
        ConfigField password = text("密码", true, null, "基础", "PASSWORD", null, null);
        password.setType("string");
        password.sensitive();
        schema.put("password", password);

        // urlOverride 的 required 只在它可见（urlMode=override）时才被前端生成校验规则，
        // 隐藏字段的必填由通用适配层跳过，这里不需要为两种模式各写一份 schema。
        ConfigField urlOverride = text("自定义 JDBC URL", true, null, "高级", "TEXTAREA", null,
            "仅在「连接串来源＝自定义 JDBC URL」时使用；仍会过参数黑名单（禁 socketFactory / autoDeserialize 等高危参数），"
                + "超时类参数由平台统一控制，填了会被丢弃");
        urlOverride.setShowWhen(new ShowWhen("urlMode", "override", null, null, null));
        schema.put("urlOverride", urlOverride);
        ConfigField driverProps = text("驱动属性", false, Map.of(), "高级", "KEY_VALUE_MAP", null,
            "逃生舱：直接传给驱动的键值对。禁填 user / password（凭证只从上方基础项注入）");
        driverProps.setType("object");
        schema.put("driverProps", driverProps);

        schema.put("connectTimeoutMs", number("连接超时（毫秒）", false,
            JdbcConnectionCfg.DEFAULT_CONNECT_TIMEOUT_MS, "高级", null,
            "翻译成各库自己的连接串参数（不用 DriverManager.setLoginTimeout，那是 JVM 全局开关）；测试连接时强制压到 3000"));
        schema.put("queryTimeoutSec", number("查询超时（秒）", false,
            JdbcConnectionCfg.DEFAULT_QUERY_TIMEOUT_SEC, "高级", null, "硬上限 300，防慢查询占满连接池"));
        schema.put("maxPoolSize", number("连接池上限", false, JdbcConnectionCfg.DEFAULT_MAX_POOL_SIZE, "高级", null,
            "硬上限 50"));
        schema.put("minIdle", number("最小空闲连接", false, 1, "高级", null, null));
        schema.put("maxRows", number("单次查询行数上限", false, JdbcConnectionCfg.DEFAULT_MAX_ROWS, "高级", null,
            "硬上限 10000，防 select * from 大表把数据空间打爆"));
        schema.put("fetchSize", number("抓取大小", false, JdbcConnectionCfg.DEFAULT_FETCH_SIZE, "高级", null,
            "PostgreSQL 系需临时关闭 autoCommit 才生效，连接器已自动处理"));
        ConfigField readOnly = bool("只读连接", false, false, "高级",
            "开启后拒绝执行非 SELECT 语句（弱防线，建议同时使用只读数据库账号）");
        schema.put("readOnly", readOnly);
        ConfigField columnNameCase = text("列名大小写", false, "AS_IS", "高级", "SELECT", null,
            "Oracle、达梦默认返回大写列名，换库会断链路；LOWER / UPPER 可统一");
        columnNameCase.setOptions(List.of(new Option("原样", "AS_IS"), new Option("全小写", "LOWER"),
            new Option("全大写", "UPPER")));
        schema.put("columnNameCase", columnNameCase);
        schema.put("decimalAsString", bool("高精度数转字符串", false, true, "高级",
            "NUMERIC / DECIMAL 转字符串输出，防前端 JSON 解析丢精度"));
        schema.put("logSql", bool("记录 SQL 原文", false, false, "高级",
            "默认关闭：args 可能含身份证等敏感数据。开启后也只回显每个参数的前 8 个字符"));
        return schema;
    }

    /** §3.3 / §8.1：query 的参数与产出 */
    private OperationDef queryOperation() {
        OperationDef op = operation(OPERATION_QUERY, "SQL 查询", CMP_QUERY,
            List.of("rows", "row", "value", "rowCount", "columns"));
        op.getParams().put("sql", text("SQL", true, null, null, "TEXTAREA", null,
            "PreparedStatement + ? 绑定，SQL 文本原样透传不做方言转换"));
        ConfigField args = ConfigField.ofArray("绑定参数", false, List.of());
        args.setWidget("JSON");
        args.setDescription("按 ? 顺序给的数组，元素支持 {{ $.路径 }} 表达式（由组件层解析）");
        op.getParams().put("args", args);
        ConfigField resultMode = text("结果形态", false, "rows", null, "SELECT", null,
            "rows＝全部行 / first＝首行 / scalar＝首行首列标量");
        resultMode.setOptions(List.of(new Option("全部行", "rows"), new Option("首行", "first"),
            new Option("标量", "scalar")));
        op.getParams().put("resultMode", resultMode);
        ConfigField scalarType = text("标量类型", false, "string", null, "SELECT", null,
            "仅 resultMode=scalar 时生效");
        scalarType.setOptions(List.of(new Option("字符串", "string"), new Option("整数", "int"),
            new Option("长整数", "long"), new Option("浮点数", "double")));
        scalarType.setShowWhen(new ShowWhen("resultMode", "scalar", null, null, null));
        op.getParams().put("scalarType", scalarType);
        op.getParams().put("maxRows", number("行数上限", false, null, null, null, "留空则用连接配置的值"));
        op.getParams().put("fetchSize", number("抓取大小", false, null, null, null, "留空则用连接配置的值"));
        return op;
    }

    /** §3.3 / §8.1：execute 的参数与产出 */
    private OperationDef executeOperation() {
        OperationDef op = operation(OPERATION_EXECUTE, "SQL 执行", CMP_EXECUTE,
            List.of("affectedRows", "generatedKeys"));
        op.getParams().put("sql", text("SQL", true, null, null, "TEXTAREA", null,
            "INSERT / UPDATE / DELETE / DDL；单组件内包一个事务，组件结束 commit、异常 rollback。"
                + "跨节点事务 v1 不支持"));
        ConfigField args = ConfigField.ofArray("绑定参数", false, List.of());
        args.setWidget("JSON");
        op.getParams().put("args", args);
        op.getParams().put("returnGeneratedKeys",
            bool("回填自增主键", false, false, null, "开启后 generatedKeys 返回数据库生成的主键"));
        return op;
    }

    /** §3.3 / §8.1：batch 的参数与产出 */
    private OperationDef batchOperation() {
        OperationDef op = operation(OPERATION_BATCH, "批量执行", CMP_BATCH,
            List.of("affectedRows", "batchCount"));
        op.getParams().put("sql", text("SQL", true, null, null, "TEXTAREA", null,
            "同一条语句配多组参数；整体包在一个事务里，任一批失败全部回滚"));
        ConfigField argsList = ConfigField.ofArray("参数组", false, List.of());
        argsList.setWidget("JSON");
        argsList.setDescription("数组的数组，每个内层数组是一组 ? 绑定参数");
        op.getParams().put("argsList", argsList);
        op.getParams().put("batchSize", number("批大小", false, DEFAULT_BATCH_SIZE, null, null,
            "每攒够这么多组 executeBatch 一次"));
        return op;
    }

    // ---------------------------- 探活 ----------------------------

    /**
     * 测试连接（§3.5 六步）：建 URL（过黑名单）→ 取驱动 → {@code driver.connect} → 跑探活语句 →
     * 关连接（<b>不建池</b>）→ 结果归一化。
     *
     * <p>超时强制压到 3000ms，且写进连接串而不是 {@code DriverManager.setLoginTimeout()}。
     *
     * <p>失败只回三态（可达 / 超时 / 拒绝连接 / 认证失败），<b>不回显 SQLException 原文</b>——
     * 原文含内网真实 IP、库名、驱动版本，是 SSRF 回读通道（§3.5 unverified 严档）。
     * 完整堆栈进服务端 {@code log.error}。verified 档的 SQLState + 归一化 message 增强留待阶段 C。
     *
     * @return {@code Map}，含 {@code message}（前端直显）/ {@code status} / {@code product} /
     * {@code version} / {@code elapsedMs}
     */
    @Override
    public Object testConnection(Connection connection) {
        JdbcConnectionCfg cfg = JdbcConnectionCfg.from(dbType(), connection.getConfig()).forProbe();
        cfg.requireConnectable();
        // 探活两项超时都固定 3000：建连超时管「连不上多久放弃」，读写超时管「连上后 SELECT 1
        // 多久没响应放弃」。后者不能走 cfg.readTimeoutMs()（那按 queryTimeoutSec 算出 35 秒），
        // 否则 §3.5 第 1 条 SSRF 慢速探测的 3 秒收口就废了。
        String url = JdbcUrlBuilder.buildUrl(dbType(), cfg, JdbcConnectionCfg.PROBE_TIMEOUT_MS, JdbcConnectionCfg.PROBE_TIMEOUT_MS);
        Properties props = JdbcUrlBuilder.buildProps(dbType(), cfg, JdbcConnectionCfg.PROBE_TIMEOUT_MS, JdbcConnectionCfg.PROBE_TIMEOUT_MS);
        Driver driver = JdbcDriverRegistry.driverFor(dbType());
        long start = System.currentTimeMillis();
        try (java.sql.Connection c = driver.connect(url, props)) {
            if (c == null) {
                throw new ConnectorException("JDBC_TEST_CONNECTION_FAILED",
                    "驱动不接受该 JDBC URL（请检查主机 / 端口 / 库名是否正确）");
            }
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(PROBE_QUERY_TIMEOUT_SEC);
                st.execute(dbType().getProbeSql());
            }
            DatabaseMetaData meta = c.getMetaData();
            String product = meta.getDatabaseProductName();
            String version = meta.getDatabaseProductVersion();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("message", "连接成功，" + product + " " + version);
            result.put("product", product);
            result.put("version", version);
            // §4.2.1：驱动真实生效版本，与 manifest.driver.version 比对，对不上说明包里那份没生效
            result.put("driverVersion", meta.getDriverVersion());
            result.put("elapsedMs", System.currentTimeMillis() - start);
            log.info("[db] 探活成功 type={} product={} {} ms={}", getType(), product, version,
                result.get("elapsedMs"));
            return result;
        } catch (SQLException e) {
            log.error("[db] 探活失败 type={}", getType(), e);
            throw new ConnectorException("JDBC_TEST_CONNECTION_FAILED", classify(e), e);
        }
    }

    // ---------------------------- 能力接口 ----------------------------

    @Override
    public QueryResult query(Connection conn, String sql, List<Object> args, QueryOptions opts) {
        JdbcConnectionCfg cfg = JdbcConnectionCfg.from(dbType(), conn.getConfig());
        JdbcSqlGuard.requireNonBlank(sql);
        JdbcSqlGuard.checkReadOnly(sql, cfg.readOnly());
        QueryOptions options = opts == null ? new QueryOptions(null, null, null, null, null) : opts;
        String resultMode = normalizeResultMode(options.resultMode());
        JdbcConnectionCfg effective = cfg.withColumnNameCase(options.columnNameCase());
        int maxRows = clamp(options.maxRows() == null ? cfg.maxRows() : options.maxRows(),
            1, JdbcConnectionCfg.MAX_ROWS_HARD_LIMIT);
        int fetchSize = Math.max(0, options.fetchSize() == null ? cfg.fetchSize() : options.fetchSize());

        DataSource dataSource = pools.dataSource(conn.getId(), dbType(), cfg);
        long start = System.nanoTime();
        try (java.sql.Connection c = dataSource.getConnection()) {
            // PostgreSQL 只在 autoCommit=false 时才按 fetchSize 分批取数（§7.2），其余库无害
            boolean restoreAutoCommit = false;
            if (fetchSize > 0 && c.getAutoCommit()) {
                c.setAutoCommit(false);
                restoreAutoCommit = true;
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                applyStatement(ps, cfg, maxRows, fetchSize);
                bind(ps, args);
                try (ResultSet rs = ps.executeQuery()) {
                    QueryResult result = collect(rs, effective, resultMode, options.scalarType());
                    logOp(OPERATION_QUERY, conn, sql, args, cfg, result.rowCount(), start);
                    return result;
                }
            } finally {
                if (restoreAutoCommit) {
                    rollbackQuietly(c);
                    restoreAutoCommit(c, true);
                }
            }
        } catch (SQLException e) {
            log.error("[db] 查询失败 connId={} type={}", conn.getId(), getType(), e);
            throw new ConnectorException("JDBC_QUERY_FAILED", "SQL 查询失败：" + classify(e), e);
        }
    }

    @Override
    public ExecuteResult execute(Connection conn, String sql, List<Object> args, boolean returnGeneratedKeys) {
        JdbcConnectionCfg cfg = JdbcConnectionCfg.from(dbType(), conn.getConfig());
        JdbcSqlGuard.requireNonBlank(sql);
        // 只读连接上 execute 必然被拒（写语句不在放行关键字里）——§9.1 P1
        JdbcSqlGuard.checkReadOnly(sql, cfg.readOnly());

        DataSource dataSource = pools.dataSource(conn.getId(), dbType(), cfg);
        long start = System.nanoTime();
        try (java.sql.Connection c = dataSource.getConnection()) {
            boolean original = c.getAutoCommit();
            // §6.2：单组件内一个事务，结束 commit、异常 rollback（跨节点事务 v1 不支持）
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = returnGeneratedKeys
                    ? c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                    : c.prepareStatement(sql)) {
                    applyStatement(ps, cfg, 0, 0);
                    bind(ps, args);
                    int affected = ps.executeUpdate();
                    List<Object> keys = returnGeneratedKeys ? generatedKeys(ps, cfg) : List.of();
                    c.commit();
                    logOp(OPERATION_EXECUTE, conn, sql, args, cfg, affected, start);
                    return new ExecuteResult(affected, keys);
                }
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                restoreAutoCommit(c, original);
            }
        } catch (SQLException e) {
            log.error("[db] 执行失败 connId={} type={} stmt={}", conn.getId(), getType(),
                JdbcSqlGuard.statementType(sql), e);
            throw new ConnectorException("JDBC_EXECUTE_FAILED", "SQL 执行失败：" + classify(e), e);
        }
    }

    @Override
    public BatchResult batch(Connection conn, String sql, List<List<Object>> argsList, int batchSize) {
        JdbcConnectionCfg cfg = JdbcConnectionCfg.from(dbType(), conn.getConfig());
        JdbcSqlGuard.requireNonBlank(sql);
        JdbcSqlGuard.checkReadOnly(sql, cfg.readOnly());
        if (argsList == null || argsList.isEmpty()) {
            return new BatchResult(List.of(), 0);
        }
        int size = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;

        DataSource dataSource = pools.dataSource(conn.getId(), dbType(), cfg);
        long start = System.nanoTime();
        try (java.sql.Connection c = dataSource.getConnection()) {
            boolean original = c.getAutoCommit();
            // §6.2：batch 不关 autoCommit 性能差一个数量级
            c.setAutoCommit(false);
            try {
                List<Integer> affected = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setQueryTimeout(cfg.queryTimeoutSec());
                    int pending = 0;
                    for (List<Object> args : argsList) {
                        bind(ps, args);
                        ps.addBatch();
                        pending++;
                        if (pending >= size) {
                            affected.addAll(toList(ps.executeBatch()));
                            ps.clearBatch();
                            pending = 0;
                        }
                    }
                    if (pending > 0) {
                        affected.addAll(toList(ps.executeBatch()));
                    }
                }
                c.commit();
                logOp(OPERATION_BATCH, conn, sql, null, cfg, argsList.size(), start);
                return new BatchResult(affected, argsList.size());
            } catch (SQLException | RuntimeException e) {
                rollbackQuietly(c);
                throw e;
            } finally {
                restoreAutoCommit(c, original);
            }
        } catch (SQLException e) {
            log.error("[db] 批量执行失败 connId={} type={} stmt={} groups={}", conn.getId(), getType(),
                JdbcSqlGuard.statementType(sql), argsList.size(), e);
            throw new ConnectorException("JDBC_BATCH_FAILED", "批量执行失败（已回滚）：" + classify(e), e);
        }
    }

    // ---------------------------- 通用逃生舱 ----------------------------

    /**
     * operation 分发（§1.4.2 逃生舱）：三个 operation 落到同一套能力接口实现，
     * 保证「不改平台、不引 api 也能从脚本调」。
     */
    @Override
    public Object invoke(Connection connection, String operation, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        String op = operation == null ? "" : operation.trim();
        return switch (op) {
            case OPERATION_QUERY -> query(connection, str(p, "sql"), argList(p.get("args")),
                new QueryOptions(str(p, "resultMode"), str(p, "scalarType"),
                    integer(p, "maxRows"), integer(p, "fetchSize"), null));
            case OPERATION_EXECUTE -> execute(connection, str(p, "sql"), argList(p.get("args")),
                boolParam(p, "returnGeneratedKeys"));
            case OPERATION_BATCH -> batch(connection, str(p, "sql"), argMatrix(p.get("argsList")),
                integerOr(p, "batchSize", DEFAULT_BATCH_SIZE));
            default -> throw new ConnectorException("OPERATION_NOT_SUPPORTED",
                "连接器 " + getType() + " 未实现 operation=" + operation + "（支持 query / execute / batch）");
        };
    }

    // ---------------------------- 生命周期（§3.1 表格） ----------------------------

    /** 连接增删改 → 关这一个连接的池（挂钩 ①②③） */
    @Override
    public void onConnectionChanged(String connectionId) {
        pools.evict(connectionId);
    }

    /**
     * def 行停用 → 关<b>这个库</b>的全部池（挂钩 ④）。别的库不受影响（每包一份 registry）。
     * <b>不注销驱动</b>：停用可逆，再启用还要用驱动类。
     */
    @Override
    public void onSuspend() {
        pools.evictAll();
        log.info("[db] 连接器 {} 已停用，关闭全部连接池（保留驱动）", getType());
    }

    /**
     * 卸载 / 应用关闭 → 关池 + 注销驱动（挂钩 ⑤⑥，§4.3 第 1、2 步）。
     *
     * <p><b>必须在包内执行</b>：{@code DriverManager.getDrivers()} 与 {@code deregisterDriver()}
     * 都是 {@code @CallerSensitive}，方法体在包内 → caller 是 pluginLoader → 能看见并注销插件驱动。
     * 平台侧 {@code PluginManager.unload()} 只负责调用本方法，之后才是 {@code loader.close()}。
     */
    @Override
    public void onShutdown() {
        pools.evictAll();
        JdbcDriverRegistry.deregisterAll();
        log.info("[db] 连接器 {} 已卸载（池已关、驱动已注销）", getType());
    }

    // ---------------------------- 私有实现 ----------------------------

    /** 按 resultMode 收集结果（§3.4：resultMode 决定 rows / row / value 哪个有值） */
    private QueryResult collect(ResultSet rs, JdbcConnectionCfg cfg, String resultMode, String scalarType)
        throws SQLException {
        List<String> columns = columnsOf(rs, cfg);
        if ("scalar".equals(resultMode)) {
            ResultSetMetaData meta = rs.getMetaData();
            int firstType = meta.getColumnCount() > 0 ? meta.getColumnType(1) : Types.VARCHAR;
            Object value = JdbcResultMapper.readScalar(rs, firstType, cfg, scalarType);
            return new QueryResult(List.of(), Map.of(), value, value == null ? 0 : 1, columns);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if ("first".equals(resultMode)) {
            JdbcResultMapper.readRows(rs, cfg, row -> {
                rows.add(row);
                return false;
            });
            Map<String, Object> first = rows.isEmpty() ? null : rows.get(0);
            return new QueryResult(List.of(), first, null, first == null ? 0 : 1, columns);
        }
        JdbcResultMapper.readRows(rs, cfg, row -> {
            rows.add(row);
            return true;
        });
        return new QueryResult(rows, rows.isEmpty() ? null : rows.get(0), null, rows.size(), columns);
    }

    /** 列名清单（getColumnLabel + columnNameCase 归一，§7.1） */
    private List<String> columnsOf(ResultSet rs, JdbcConnectionCfg cfg) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<String> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null ? "column" + i : JdbcResultMapper.normalizeColumn(label, cfg.columnNameCase()));
        }
        return columns;
    }

    /** 自增主键回填；驱动不支持时返回空表而不抛错（不是所有库/所有语句都有生成键） */
    private List<Object> generatedKeys(PreparedStatement ps, JdbcConnectionCfg cfg) {
        try (ResultSet rs = ps.getGeneratedKeys()) {
            ResultSetMetaData meta = rs.getMetaData();
            if (meta.getColumnCount() == 0) {
                return List.of();
            }
            int type = meta.getColumnType(1);
            List<Object> keys = new ArrayList<>();
            while (rs.next()) {
                keys.add(JdbcResultMapper.readColumn(rs, 1, type, cfg));
            }
            return keys;
        } catch (SQLException | RuntimeException e) {
            log.warn("[db] 读取自增主键失败（忽略，不影响执行结果）：{}", e.toString());
            return List.of();
        }
    }

    /** Statement 公共设置：查询超时 + 行数上限 + 抓取大小（§7.2） */
    private void applyStatement(PreparedStatement ps, JdbcConnectionCfg cfg, int maxRows, int fetchSize)
        throws SQLException {
        ps.setQueryTimeout(cfg.queryTimeoutSec());
        if (maxRows > 0) {
            ps.setMaxRows(maxRows);
        }
        if (fetchSize > 0) {
            ps.setFetchSize(fetchSize);
        }
    }

    /** 参数绑定：null 走 setNull，常见 java.time 类型转 JDBC 时间类型，其余交给驱动 */
    private void bind(PreparedStatement ps, List<Object> args) throws SQLException {
        if (args == null || args.isEmpty()) {
            return;
        }
        for (int i = 0; i < args.size(); i++) {
            setObject(ps, i + 1, args.get(i));
        }
    }

    private void setObject(PreparedStatement ps, int index, Object value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.NULL);
            return;
        }
        switch (value) {
            case LocalDateTime dateTime -> ps.setTimestamp(index, java.sql.Timestamp.valueOf(dateTime));
            case LocalDate date -> ps.setDate(index, java.sql.Date.valueOf(date));
            case LocalTime time -> ps.setTime(index, java.sql.Time.valueOf(time));
            case java.util.Date date -> ps.setTimestamp(index, new java.sql.Timestamp(date.getTime()));
            case java.math.BigDecimal decimal -> ps.setBigDecimal(index, decimal);
            case byte[] bytes -> ps.setBytes(index, bytes);
            case Collection<?> collection -> ps.setObject(index, collection.toArray());
            default -> ps.setObject(index, value);
        }
    }

    /** §7.3 日志脱敏：默认不记 SQL 原文与 args；开启后 args 也只记个数与前 8 字符 */
    private void logOp(String op, Connection conn, String sql, List<Object> args, JdbcConnectionCfg cfg,
                       int rows, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        int argsCount = args == null ? 0 : args.size();
        // 语句首关键字始终记（§9.1 P1 语句类型审计），它不含用户数据
        String stmt = JdbcSqlGuard.statementType(sql);
        if (cfg.logSql()) {
            log.info("[db] op={} connId={} stmt={} sql={} argsCount={} args={} rows={} ms={}",
                op, conn.getId(), stmt, sql, argsCount, previewArgs(args), rows, ms);
        } else {
            log.info("[db] op={} connId={} stmt={} argsCount={} rows={} ms={}",
                op, conn.getId(), stmt, argsCount, rows, ms);
        }
    }

    /** args 回显：每个参数只留前 8 字符 + ***（§7.3） */
    private String previewArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "[]";
        }
        List<String> preview = new ArrayList<>(args.size());
        for (Object arg : args) {
            String s = arg == null ? "null" : String.valueOf(arg);
            preview.add(s.length() <= ARG_PREVIEW_LEN ? s : s.substring(0, ARG_PREVIEW_LEN) + "***");
        }
        return preview.toString();
    }

    /**
     * SQLException → 三态归一化（§3.5）。不回原文：内网 IP / 库名 / 驱动版本都是 SSRF 回读素材。
     */
    private String classify(SQLException e) {
        String state = e.getSQLState();
        if (e instanceof SQLTimeoutException) {
            return "连接或查询超时";
        }
        if (state != null && state.startsWith("08")) {
            return "拒绝连接（数据库不可达或端口不通）";
        }
        if ("28000".equals(state) || "28P01".equals(state)) {
            return "认证失败（用户名或密码不正确）";
        }
        if (state != null && state.startsWith("3D")) {
            return "数据库不存在";
        }
        String message = String.valueOf(e.getMessage()).toLowerCase();
        if (message.contains("timeout") || message.contains("timed out")) {
            return "连接或查询超时";
        }
        if (message.contains("refused") || message.contains("unreachable")) {
            return "拒绝连接（数据库不可达或端口不通）";
        }
        if (message.contains("password") || message.contains("authentication") || message.contains("login")) {
            return "认证失败（用户名或密码不正确）";
        }
        return "连接失败（详见服务端日志）";
    }

    private void rollbackQuietly(java.sql.Connection c) {
        try {
            if (!c.isClosed()) {
                c.rollback();
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("[db] 回滚失败：{}", e.toString());
        }
    }

    private void restoreAutoCommit(java.sql.Connection c, boolean value) {
        try {
            if (!c.isClosed() && c.getAutoCommit() != value) {
                c.setAutoCommit(value);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("[db] 恢复 autoCommit 失败（连接即将归还池，HikariCP 会重置）：{}", e.toString());
        }
    }

    private String normalizeResultMode(String resultMode) {
        if (resultMode == null || resultMode.isBlank()) {
            return "rows";
        }
        String mode = resultMode.trim().toLowerCase();
        if (!RESULT_MODES.contains(mode)) {
            throw new ConnectorException("JDBC_RESULT_MODE_INVALID",
                "resultMode 取值不合法（rows / first / scalar）：" + resultMode);
        }
        return mode;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<Integer> toList(int[] batchResult) {
        List<Integer> out = new ArrayList<>(batchResult.length);
        for (int value : batchResult) {
            out.add(value);
        }
        return out;
    }

    // ---------------------------- invoke 参数解析 ----------------------------

    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static boolean boolParam(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static Integer integer(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new ConnectorException("JDBC_PARAM_INVALID", "参数 " + key + " 不是整数：" + v);
        }
    }

    private static int integerOr(Map<String, Object> params, String key, int defaultValue) {
        Integer v = integer(params, key);
        return v == null ? defaultValue : v;
    }

    /** args：接受 List，也接受单个非 List 值（自动包成一组），null 转空表 */
    private static List<Object> argList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (raw instanceof Object[] array) {
            return List.of(array);
        }
        return List.of(raw);
    }

    /** argsList：数组的数组；内层元素按 argList 同样宽容处理 */
    private static List<List<Object>> argMatrix(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof Collection<?> collection)) {
            throw new ConnectorException("JDBC_PARAM_INVALID", "argsList 必须是数组的数组");
        }
        List<List<Object>> out = new ArrayList<>(collection.size());
        for (Object item : collection) {
            out.add(argList(item));
        }
        return out;
    }

    // ---------------------------- schema 构造小工具 ----------------------------

    private static OperationDef operation(String name, String label, String componentId, List<String> outputs) {
        OperationDef op = new OperationDef();
        op.setName(name);
        op.setLabel(label);
        op.setComponentId(componentId);
        op.setOutputs(new ArrayList<>(outputs));
        return op;
    }

    private static ConfigField text(String label, boolean required, Object defaultValue, String group,
                                    String widget, String placeholder, String description) {
        ConfigField field = new ConfigField();
        field.setType("string");
        field.setLabel(label);
        field.setRequired(required);
        field.setDefaultValue(defaultValue);
        field.setGroup(group);
        field.setWidget(widget == null ? "TEXT" : widget);
        field.setPlaceholder(placeholder);
        field.setDescription(description);
        return field;
    }

    private static ConfigField number(String label, boolean required, Integer defaultValue, String group,
                                      String placeholder, String description) {
        ConfigField field = new ConfigField();
        field.setType("int");
        field.setLabel(label);
        field.setRequired(required);
        field.setDefaultValue(defaultValue);
        field.setGroup(group);
        field.setWidget("NUMBER");
        field.setPlaceholder(placeholder);
        field.setDescription(description);
        return field;
    }

    private static ConfigField bool(String label, boolean required, boolean defaultValue, String group,
                                    String description) {
        ConfigField field = new ConfigField();
        field.setType("boolean");
        field.setLabel(label);
        field.setRequired(required);
        field.setDefaultValue(defaultValue);
        field.setGroup(group);
        field.setWidget("BOOLEAN");
        field.setDescription(description);
        return field;
    }
}
