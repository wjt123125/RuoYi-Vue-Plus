package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code Connection.config}（Map）→ 强类型（方案 §3.1，对齐 bpm 侧 BpmHttpConnectionCfg）。
 *
 * <p>用 record 而非 Jackson 反序列化：连接器不引平台 {@code JsonCodec}（§2.1.2），而为一个 Map 拉进
 * {@code tools.jackson} 也不划算——字段就十几个，手工读键顺带把默认值与硬上限一次做完，
 * 比「先反序列化再逐字段校正」更直白。
 *
 * <p>三处硬上限在此收口（§7.2 / §3.2）：{@code maxPoolSize} ≤ 50、{@code maxRows} ≤ 10000、
 * {@code queryTimeoutSec} ≤ 300。超了就压到上限 + {@code log.warn}，不抛异常——用户配错一个数字
 * 不该让整条链路失败，但也不能真的按他写的值去 OOM 数据空间。
 *
 * @param host             主机
 * @param port             端口
 * @param database         数据库名 / 服务名 / 模式（语义见 {@link JdbcDbType#getDatabaseLabel()}）
 * @param username         用户名
 * @param password         密码（平台侧 sensitive 字段，从 credentials 加密列解出后并回 config）
 * @param urlOverride      自定义 JDBC URL；填了则跳过 host/port/database 拼装，仍过参数黑名单
 * @param driverProps      驱动属性逃生舱；键过黑名单，禁 user/password
 * @param connectTimeoutMs 连接超时毫秒（探活时被强制压到 3000，§3.5）
 * @param queryTimeoutSec  查询超时秒（{@code Statement.setQueryTimeout}）
 * @param maxPoolSize      HikariCP maximumPoolSize（≤ 50）
 * @param minIdle          HikariCP minimumIdle
 * @param maxRows          单次查询行数上限（≤ 10000）
 * @param fetchSize        抓取大小
 * @param readOnly         只读连接（{@code Connection.setReadOnly} + {@link JdbcSqlGuard} 拒非 SELECT）
 * @param columnNameCase   列名归一：AS_IS / LOWER / UPPER
 * @param decimalAsString  NUMERIC/DECIMAL 是否转字符串（默认 true，防精度丢失）
 * @param logSql           是否记 SQL 原文（默认 false，§7.3）
 * @author databus
 */
public record JdbcConnectionCfg(String host,
                                int port,
                                String database,
                                String username,
                                String password,
                                String urlOverride,
                                Map<String, String> driverProps,
                                int connectTimeoutMs,
                                int queryTimeoutSec,
                                int maxPoolSize,
                                int minIdle,
                                int maxRows,
                                int fetchSize,
                                boolean readOnly,
                                String columnNameCase,
                                boolean decimalAsString,
                                boolean logSql) {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectionCfg.class);

    /** 连接超时默认值（§3.2） */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;

    /** 探活固定超时（§3.5 第 1 步，P0 收口） */
    public static final int PROBE_TIMEOUT_MS = 3000;

    /** 查询超时默认 / 硬上限（§7.2） */
    public static final int DEFAULT_QUERY_TIMEOUT_SEC = 30;
    public static final int MAX_QUERY_TIMEOUT_SEC = 300;

    /**
     * 读写超时（{@code socketTimeout}）在 {@code queryTimeoutSec} 之上额外留的余量毫秒。
     *
     * <p>留余量是为了让 {@code Statement.setQueryTimeout} 先触发：那才是「查询超时」的正常出口
     * （报错清晰、连接可回收）；socketTimeout 先到只会得到一句
     * {@code CommunicationsException: Read timed out}，且连接已废。
     */
    public static final int READ_TIMEOUT_MARGIN_MS = 5000;

    /** 连接池上限默认 / 硬上限（§6.1） */
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final int MAX_POOL_SIZE = 50;

    /** 单次查询行数默认 / 硬上限（§7.2：数据空间在内存/Redis，防 select * 大表 OOM） */
    public static final int DEFAULT_MAX_ROWS = 1000;
    public static final int MAX_ROWS_HARD_LIMIT = 10000;

    /** 抓取大小默认（§7.2） */
    public static final int DEFAULT_FETCH_SIZE = 100;

    /** 列名归一合法取值 */
    private static final Set<String> COLUMN_NAME_CASES = Set.of("AS_IS", "LOWER", "UPPER");

    /**
     * configSchema 里 {@code urlMode} 单选的「按主机拼装」档（另一档 {@code "override"} 纯 UI 用，
     * 连接器只关心 basic：显式 basic 时忽略 urlOverride，见 {@link #urlOverride(Map)}）。
     */
    public static final String URL_MODE_BASIC = "basic";

    /**
     * 从 {@code Connection.config} 构造。config 为 null 视为空表（后续必填校验在
     * {@link #requireConnectable()} 里统一报）。
     *
     * @param dbType 库种（提供默认端口）
     * @param config 连接配置 Map（含平台并回的 sensitive 字段）
     */
    public static JdbcConnectionCfg from(JdbcDbType dbType, Map<String, Object> config) {
        Map<String, Object> c = config == null ? Map.of() : config;
        return new JdbcConnectionCfg(
            str(c, "host", null),
            intVal(c, "port", dbType.getDefaultPort(), 1, 65535, "port"),
            str(c, "database", null),
            str(c, "username", null),
            str(c, "password", null),
            urlOverride(c),
            stringMap(c, "driverProps"),
            intVal(c, "connectTimeoutMs", DEFAULT_CONNECT_TIMEOUT_MS, 1, 600_000, "connectTimeoutMs"),
            intVal(c, "queryTimeoutSec", DEFAULT_QUERY_TIMEOUT_SEC, 1, MAX_QUERY_TIMEOUT_SEC, "queryTimeoutSec"),
            intVal(c, "maxPoolSize", DEFAULT_MAX_POOL_SIZE, 1, MAX_POOL_SIZE, "maxPoolSize"),
            intVal(c, "minIdle", 1, 0, MAX_POOL_SIZE, "minIdle"),
            intVal(c, "maxRows", DEFAULT_MAX_ROWS, 1, MAX_ROWS_HARD_LIMIT, "maxRows"),
            intVal(c, "fetchSize", DEFAULT_FETCH_SIZE, 0, 100_000, "fetchSize"),
            bool(c, "readOnly", false),
            columnNameCase(c),
            bool(c, "decimalAsString", true),
            bool(c, "logSql", false));
    }

    /**
     * 读 urlOverride，并尊重 configSchema 里的模式开关 {@code urlMode}。
     *
     * <p>host/port/database 与 urlOverride 是两条互斥路径，前端用 urlMode 单选切换显示。
     * 切换后旧值仍留在 config 里（表单不会替用户删数据），若不看这个键就会出现
     * 「界面填的是主机、实际连的是另一条 URL」的静默错位。
     *
     * <p>只在<b>显式</b> basic 时丢弃：本次改造前存的 config 没有 urlMode 键，
     * 保持原行为（urlOverride 非空即优先），避免升级后老连接突然连不上。
     */
    private static String urlOverride(Map<String, Object> c) {
        return URL_MODE_BASIC.equals(str(c, "urlMode", null)) ? null : str(c, "urlOverride", null);
    }

    /**
     * 探活专用副本：超时压到 3000ms（§3.5「探活时强制压到 3000」）。其余字段原样。
     *
     * <p><b>注意</b>：探活路径<b>不要</b>用本副本的 {@link #readTimeoutMs()}——那会按
     * {@code queryTimeoutSec}（默认 30 秒）算出 35 秒的读写超时，把「探活 3 秒收口」这条 P0
     * 约束废掉（SSRF 慢速探测正是靠它兜底）。探活的建连与读写超时都固定传
     * {@link #PROBE_TIMEOUT_MS}，见 {@code AbstractJdbcConnector#testConnection}。
     */
    public JdbcConnectionCfg forProbe() {
        return new JdbcConnectionCfg(host, port, database, username, password, urlOverride, driverProps,
            PROBE_TIMEOUT_MS, queryTimeoutSec, maxPoolSize, minIdle, maxRows, fetchSize, readOnly,
            columnNameCase, decimalAsString, logSql);
    }

    /**
     * 建池路径的读写超时（{@code socketTimeout}）取值：罩住 {@code queryTimeoutSec} 再留余量，
     * 且不小于 {@code connectTimeoutMs}。
     *
     * <p>只对 {@link JdbcDbType#isReadTimeoutKey(String)} 为真的库生效（当前 MySQL / MariaDB）。
     * 其余库的 {@code timeoutKeys} 全是建连超时语义，读写超时交给 {@code Statement.setQueryTimeout}，
     * 本方法不会被调用。
     *
     * <p>为什么不能沿用 {@code connectTimeoutMs}：那默认只有 3000ms，而 socketTimeout 管的是
     * 「连上之后单次 socket 读多久没数据就断」——同值注入会让任何超过 3 秒的查询在 socket 层
     * 直接断连，{@code queryTimeoutSec=30} 形同虚设。详见 {@link JdbcDbType} 的 READ_TIMEOUT_KEYS。
     *
     * @return 读写超时毫秒数（默认配置下为 35000）
     */
    public int readTimeoutMs() {
        return Math.max(connectTimeoutMs, queryTimeoutSec * 1000 + READ_TIMEOUT_MARGIN_MS);
    }

    /**
     * 建连必填校验：没填 {@code urlOverride} 时 host/port/database 必须有；username 一律必填。
     * 密码允许为空（部分内网库免密），故不校验。
     *
     * @throws ConnectorException 缺字段时抛 {@code JDBC_CONFIG_MISSING}
     */
    public void requireConnectable() {
        if (isBlank(urlOverride)) {
            if (isBlank(host)) {
                throw new ConnectorException("JDBC_CONFIG_MISSING", "缺少必填配置 host（或改用高级项「自定义 JDBC URL」）");
            }
            if (isBlank(database)) {
                throw new ConnectorException("JDBC_CONFIG_MISSING", "缺少必填配置 database");
            }
        }
        if (isBlank(username)) {
            throw new ConnectorException("JDBC_CONFIG_MISSING", "缺少必填配置 username");
        }
    }

    /**
     * 池 key 的 hash 部分（§6.1：{@code connectionId + ":" + sha256(config ⧺ credentials)}）。
     *
     * <p>覆盖<b>全部</b>会影响物理连接的字段——含 password 与 driverProps：用户改了密码而 hash 不变，
     * 就会继续用旧池连库，表现为「明明改了配置却没生效」。
     */
    public String fingerprint() {
        String raw = String.join("|",
            nvl(host), String.valueOf(port), nvl(database), nvl(username), nvl(password), nvl(urlOverride),
            String.valueOf(connectTimeoutMs), String.valueOf(maxPoolSize), String.valueOf(minIdle),
            String.valueOf(readOnly), sortedProps());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然带 SHA-256；真到这说明 JVM 被裁过，退化成原串也不影响正确性（只是 key 变长）
            return String.valueOf(raw.hashCode());
        }
    }

    /**
     * 列名归一方式的副本：组件级 {@code opts.columnNameCase()} 优先于连接级配置。
     * 只影响结果集列名，不影响物理连接，故不参与 {@link #fingerprint()}。
     */
    public JdbcConnectionCfg withColumnNameCase(String caseMode) {
        if (caseMode == null || caseMode.isBlank() || caseMode.equalsIgnoreCase(columnNameCase)) {
            return this;
        }
        String upper = caseMode.trim().toUpperCase();
        String effective = COLUMN_NAME_CASES.contains(upper) ? upper : columnNameCase;
        return new JdbcConnectionCfg(host, port, database, username, password, urlOverride, driverProps,
            connectTimeoutMs, queryTimeoutSec, maxPoolSize, minIdle, maxRows, fetchSize, readOnly,
            effective, decimalAsString, logSql);
    }

    // ---------------------------- 私有工具 ----------------------------

    /** driverProps 排序后拼串，保证「同样内容不同插入顺序」得到同一个 hash */
    private String sortedProps() {
        if (driverProps == null || driverProps.isEmpty()) {
            return "";
        }
        return driverProps.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + "=" + nvl(e.getValue()))
            .reduce((a, b) -> a + "," + b)
            .orElse("");
    }

    private static String columnNameCase(Map<String, Object> c) {
        String v = str(c, "columnNameCase", "AS_IS");
        String upper = v == null ? "AS_IS" : v.trim().toUpperCase();
        return COLUMN_NAME_CASES.contains(upper) ? upper : "AS_IS";
    }

    private static String str(Map<String, Object> c, String key, String defaultValue) {
        Object v = c.get(key);
        if (v == null) {
            return defaultValue;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private static boolean bool(Map<String, Object> c, String key, boolean defaultValue) {
        Object v = c.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return defaultValue;
        }
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "Y".equalsIgnoreCase(s);
    }

    /**
     * 读整数并夹到 [min, max]：非数字/留空取默认值，越界压到边界 + warn（§7.2「超过则压到硬上限」）。
     */
    private static int intVal(Map<String, Object> c, String key, int defaultValue, int min, int max, String label) {
        Object v = c.get(key);
        if (v == null) {
            return defaultValue;
        }
        int parsed;
        if (v instanceof Number n) {
            parsed = n.intValue();
        } else {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return defaultValue;
            }
            try {
                parsed = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                log.warn("[db] 配置 {}={} 不是整数，改用默认值 {}", label, s, defaultValue);
                return defaultValue;
            }
        }
        if (parsed < min) {
            log.warn("[db] 配置 {}={} 低于下限 {}，已压到下限", label, parsed, min);
            return min;
        }
        if (parsed > max) {
            log.warn("[db] 配置 {}={} 超过硬上限 {}，已压到上限（防数据空间 OOM / 打满数据库连接数）", label, parsed, max);
            return max;
        }
        return parsed;
    }

    /** 读键值对（driverProps）：值统一 String.valueOf，null 值键直接丢弃 */
    private static Map<String, String> stringMap(Map<String, Object> c, String key) {
        Object v = c.get(key);
        if (!(v instanceof Map<?, ?> raw) || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()).trim(), String.valueOf(e.getValue()));
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
