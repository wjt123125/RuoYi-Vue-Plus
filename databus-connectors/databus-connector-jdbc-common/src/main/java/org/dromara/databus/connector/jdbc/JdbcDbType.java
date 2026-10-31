package org.dromara.databus.connector.jdbc;

import java.util.List;
import java.util.Set;

/**
 * JDBC 连接器的库种白名单（方案 §9.1 P0）。
 *
 * <p>本枚举是「平台只认 7 个 {@code jdbc-*} type」这条 P0 防线的<b>唯一事实源</b>：type、驱动类名、
 * URL 模板、默认端口、探活语句、超时键、参数黑名单全部集中在此，各库子类只声明自己对应哪个常量，
 * 因此：
 * <ul>
 *   <li>用户无法自填 {@code driverClass}（§3.2 configSchema 里根本没有这个字段）；</li>
 *   <li>不存在 H2 等可 {@code INIT=RUNSCRIPT FROM 'http://...'} 直接 RCE 的库；</li>
 *   <li>新增一个库＝加一个枚举常量 + 一个 30 行子类模块 + 一个 zip 包，改不到公共逻辑。</li>
 * </ul>
 *
 * <p>关于 dameng / kingbase 的超时键：§3.5 表格给这两家留白（「阶段 D 实测确认后补，语法分别靠近
 * oracle / postgresql」）。此处按该提示先落暂定值——dameng 用毫秒单位（靠近 oracle）、kingbase 用
 * 秒单位（靠近 postgresql，kingbase8 驱动本身即 pg 派生），键名待阶段 D 连真库实测后在此调整，
 * 调整不影响其他任何代码。
 *
 * @author databus
 */
public enum JdbcDbType {

    /** PostgreSQL：超时键 {@code connectTimeout}，单位秒（{@code loginTimeout} 是它的秒级别名） */
    POSTGRESQL("jdbc-postgresql", "postgresql", "PostgreSQL JDBC 连接器",
        "org.postgresql.Driver", "jdbc:postgresql://%s:%d/%s", "?", "&",
        5432, "数据库名", "SELECT 1",
        List.of("connectTimeout"), false,
        Set.of("socketFactory", "socketFactoryArg", "sslfactory", "sslfactoryarg", "loggerLevel", "loggerFile")),

    /** MySQL：超时键 {@code connectTimeout}（建连）+ {@code socketTimeout}（读写），单位毫秒 */
    MYSQL("jdbc-mysql", "mysql", "MySQL JDBC 连接器",
        "com.mysql.cj.jdbc.Driver", "jdbc:mysql://%s:%d/%s", "?", "&",
        3306, "数据库名", "SELECT 1",
        List.of("connectTimeout", "socketTimeout"), true,
        Set.of("allowLoadLocalInfile", "allowLoadLocalInfileInPath", "allowUrlInLocalInfile",
            "autoDeserialize", "queryInterceptors", "statementInterceptors",
            "detectCustomCollations", "allowMultiQueries", "allowPublicKeyRetrieval")),

    /** MariaDB：驱动与超时语义同 MySQL（协议兼容），单独一个包以便驱动版本各自独立 */
    MARIADB("jdbc-mariadb", "mariadb", "MariaDB JDBC 连接器",
        "org.mariadb.jdbc.Driver", "jdbc:mariadb://%s:%d/%s", "?", "&",
        3306, "数据库名", "SELECT 1",
        List.of("connectTimeout", "socketTimeout"), true,
        Set.of("allowLoadLocalInfile", "allowLoadLocalInfileInPath", "allowUrlInLocalInfile",
            "autoDeserialize", "queryInterceptors", "statementInterceptors",
            "detectCustomCollations", "allowMultiQueries", "allowPublicKeyRetrieval")),

    /** Oracle：thin 驱动，URL 用 EZConnect 形态；database 字段语义是「服务名」；探活需 {@code FROM DUAL} */
    ORACLE("jdbc-oracle", "oracle", "Oracle JDBC 连接器",
        "oracle.jdbc.OracleDriver", "jdbc:oracle:thin:@//%s:%d/%s", "?", "&",
        1521, "服务名（serviceName）", "SELECT 1 FROM DUAL",
        List.of("oracle.net.CONNECT_TIMEOUT"), true,
        Set.of("javax.net.ssl.trustStore")),

    /** SQL Server：URL 参数分隔符是分号，库名走 {@code databaseName=} 而非路径段 */
    MSSQL("jdbc-mssql", "sqlserver", "SQL Server JDBC 连接器",
        "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver://%s:%d;databaseName=%s", ";", ";",
        1433, "数据库名", "SELECT 1",
        List.of("loginTimeout"), false,
        Set.of()),

    /** 达梦：database 字段语义是「模式（schema）」；超时键单位毫秒（靠近 oracle，阶段 D 实测确认） */
    DAMENG("jdbc-dameng", "dameng", "达梦 JDBC 连接器",
        "dm.jdbc.driver.DmDriver", "jdbc:dm://%s:%d?schema=%s", "?", "&",
        5236, "模式（schema）", "SELECT 1",
        List.of("connectTimeout"), true,
        Set.of()),

    /** 金仓：kingbase8 驱动为 pg 派生；database 字段语义是「模式」；超时键单位秒（阶段 D 实测确认） */
    KINGBASE("jdbc-kingbase", "kingbase", "人大金仓 JDBC 连接器",
        "com.kingbase8.Driver", "jdbc:kingbase8://%s:%d/%s", "?", "&",
        54321, "模式（schema）", "SELECT 1",
        List.of("connectTimeout"), false,
        Set.of("socketFactory", "socketFactoryArg", "sslfactory", "sslfactoryarg"));

    /** Connector type（全局唯一，与 manifest.json 的 type 逐字一致） */
    private final String type;

    /** 数据库类型标识（manifest.json 的 dbType；组件与市场按此归类） */
    private final String dbType;

    /** 展示标签 */
    private final String label;

    /** 驱动类全限定名（用户不可覆盖，§9.1 P0） */
    private final String driverClass;

    /** URL 模板，按 host / port / database 顺序 String.format */
    private final String urlTemplate;

    /** 首个附加参数的分隔符（多数库 "?"，SQL Server 与达梦用 ";" / "&"） */
    private final String firstParamSeparator;

    /** 后续附加参数的分隔符（多数库 "&"，SQL Server ";"） */
    private final String paramSeparator;

    /** 默认端口（用户可改，§3.2） */
    private final int defaultPort;

    /** database 字段的展示标签（Oracle 是服务名、达梦/金仓是模式） */
    private final String databaseLabel;

    /** 探活语句（§3.5 第 4 步） */
    private final String probeSql;

    /** 平台专属超时键（用户填了一律丢弃 + warn，§9.1） */
    private final List<String> timeoutKeys;

    /** 超时键单位是否为毫秒（false＝秒，需向上取整且最小 1，§3.5） */
    private final boolean timeoutInMillis;

    /** 本库专属 URL 参数黑名单（§9.1 表格） */
    private final Set<String> forbiddenParams;

    /**
     * {@link #timeoutKeys} 中属于「读写超时」语义的键（当前只有 MySQL 系的 {@code socketTimeout}）。
     *
     * <p>必须与建连超时分开取值：建连超时管的是「多久连不上就放弃」，读写超时管的是
     * 「连上之后单次 socket 读多久没数据就断」。两者合理值差一个数量级——建连 3 秒足够，
     * 读写却要罩住 {@code queryTimeoutSec}（默认 30 秒）。若同值注入，MySQL / MariaDB 上
     * 任何跑超过 3 秒的查询都会在 socket 层先断（{@code CommunicationsException: Read timed out}），
     * 而 {@code Statement.setQueryTimeout} 那条正常的查询超时路径永远够不着。
     *
     * <p>PostgreSQL / SQL Server / Oracle 只有建连超时键（{@code connectTimeout} / {@code loginTimeout}），
     * 读写超时交给 {@code Statement.setQueryTimeout}，故不在此列——这也是这个坑在 pg 靶子上测不出来的原因。
     */
    private static final Set<String> READ_TIMEOUT_KEYS = Set.of("socketTimeout");

    JdbcDbType(String type, String dbType, String label, String driverClass, String urlTemplate,
               String firstParamSeparator, String paramSeparator, int defaultPort, String databaseLabel,
               String probeSql, List<String> timeoutKeys, boolean timeoutInMillis,
               Set<String> forbiddenParams) {
        this.type = type;
        this.dbType = dbType;
        this.label = label;
        this.driverClass = driverClass;
        this.urlTemplate = urlTemplate;
        this.firstParamSeparator = firstParamSeparator;
        this.paramSeparator = paramSeparator;
        this.defaultPort = defaultPort;
        this.databaseLabel = databaseLabel;
        this.probeSql = probeSql;
        this.timeoutKeys = timeoutKeys;
        this.timeoutInMillis = timeoutInMillis;
        this.forbiddenParams = forbiddenParams;
    }

    public String getType() {
        return type;
    }

    public String getDbType() {
        return dbType;
    }

    public String getLabel() {
        return label;
    }

    public String getDriverClass() {
        return driverClass;
    }

    public String getUrlTemplate() {
        return urlTemplate;
    }

    public String getFirstParamSeparator() {
        return firstParamSeparator;
    }

    public String getParamSeparator() {
        return paramSeparator;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getDatabaseLabel() {
        return databaseLabel;
    }

    public String getProbeSql() {
        return probeSql;
    }

    public List<String> getTimeoutKeys() {
        return timeoutKeys;
    }

    public boolean isTimeoutInMillis() {
        return timeoutInMillis;
    }

    public Set<String> getForbiddenParams() {
        return forbiddenParams;
    }

    /**
     * 该超时键是否为「读写超时」语义（见 {@link #READ_TIMEOUT_KEYS}）。
     *
     * <p>调用方（{@link JdbcUrlBuilder}）据此决定注入哪个值：建连超时取
     * {@code connectTimeoutMs}，读写超时取 {@link JdbcConnectionCfg#readTimeoutMs()}。
     *
     * @param key 超时键名，如 {@code "socketTimeout"}
     * @return true 表示必须用读写超时值，不能跟建连超时同值
     */
    public boolean isReadTimeoutKey(String key) {
        return key != null && READ_TIMEOUT_KEYS.contains(key);
    }

    /**
     * 把平台算出的超时毫秒数翻译成本库连接串参数的值（§3.5）。
     *
     * <p>秒级参数向上取整且最小 1：1500ms 在 PostgreSQL / SQL Server 上只能落 2，不能落 0——
     * 0 在两家都表示「不限时」，等于把探活的 3 秒硬约束废掉。
     *
     * @param timeoutMs 超时毫秒数（建连键：探活固定 3000，建池用 cfg.connectTimeoutMs；
     *                  读写键：见 {@link JdbcConnectionCfg#readTimeoutMs()}）
     * @return 写进连接串的字面值
     */
    public String timeoutValue(int timeoutMs) {
        if (timeoutInMillis) {
            return String.valueOf(Math.max(1, timeoutMs));
        }
        return String.valueOf(Math.max(1, (int) Math.ceil(timeoutMs / 1000.0)));
    }

    /**
     * 按 type 反查库种（平台侧 runtime-info、诊断接口用）。
     *
     * @param type Connector type，如 "jdbc-postgresql"
     * @return 对应枚举；不在白名单内返回 null（不抛异常，由调用方决定语义）
     */
    public static JdbcDbType of(String type) {
        if (type == null) {
            return null;
        }
        for (JdbcDbType value : values()) {
            if (value.type.equals(type)) {
                return value;
            }
        }
        return null;
    }
}
