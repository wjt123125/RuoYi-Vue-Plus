package org.dromara.databus.connector.jdbc;

/**
 * JDBC 连接器常量：operation 名、组件注册名、连接器族与图标、schema 分组键、
 * 批处理与日志阈值、连接池护栏、结果映射阈值。
 *
 * <p>连接配置的默认值与硬上限仍归 {@link JdbcConnectionCfg}（其本身即常量类），
 * 本类只收连接器实现里跨类共用的业务常量。
 *
 * @author databus
 */
public final class JdbcConst {

    private JdbcConst() {
    }

    /* ---------------- operation 名 ---------------- */

    /** operation 名（§3.3，行业中立命名，不沿用 BPM rdsExecute 的 getString/getMap/... 八方法） */
    public static final String OPERATION_QUERY = "query";

    /** operation 名：SQL 执行 */
    public static final String OPERATION_EXECUTE = "execute";

    /** operation 名：批量执行 */
    public static final String OPERATION_BATCH = "batch";

    /* ---------------- 组件注册名 ---------------- */

    /** 组件注册名（§3.3 / §8.1：用户心智是「数据库」不是「JDBC」，故用 db* 前缀且不带协议名） */
    public static final String COMPONENT_QUERY = "dbQuery";

    /** 组件注册名：SQL 执行 */
    public static final String COMPONENT_EXECUTE = "dbExecute";

    /** 组件注册名：批量执行 */
    public static final String COMPONENT_BATCH = "dbBatch";

    /* ---------------- 连接器元信息 ---------------- */

    /** 连接器族（§8.3）：组件按族匹配，一个 dbQuery 服务所有 jdbc-* 库 */
    public static final String CONNECTOR_FAMILY = "jdbc";

    /** 图标（与平台 ProtocolJdbc 系组件同一枚，物料观感一致） */
    public static final String CONNECTOR_ICON = "ph:database";

    /* ---------------- schema 分组键 ---------------- */

    /** 分组键：常规（主组） */
    public static final String GROUP_MAIN = "default";

    /** 分组键：连接串 */
    public static final String GROUP_URL = "url";

    /** 分组键：高级 */
    public static final String GROUP_ADVANCED = "advanced";

    /* ---------------- 批处理与日志阈值 ---------------- */

    /** 批量默认批大小 */
    public static final int DEFAULT_BATCH_SIZE = 100;

    /** logSql=true 时每个参数只回显的前缀长度（§7.3：args 可能含身份证等敏感数据） */
    public static final int ARG_PREVIEW_LEN = 8;

    /* ---------------- 连接池护栏 ---------------- */

    /** 单包池总数上限（§6.1） */
    public static final int MAX_POOLS = 50;

    /** 单包物理连接总数上限（各池 maximumPoolSize 之和，§6.1） */
    public static final int MAX_TOTAL_CONNECTIONS = 500;

    /** 空闲连接回收时间：10 分钟（§6.1） */
    public static final long IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    /** 连接最长存活：30 分钟（§6.1，避开数据库侧 wait_timeout 静默断连） */
    public static final long MAX_LIFETIME_MS = 30 * 60 * 1000L;

    /* ---------------- 结果映射阈值 ---------------- */

    /** 单个 BLOB/CLOB 字段的截断上限：1MB（§7.2） */
    public static final int MAX_LOB_BYTES = 1024 * 1024;

    /**
     * JS Number 安全整数上限 2^53-1，与 {@code BigNumberSerializer.MAX_SAFE_INTEGER} 取同一阈值。
     * <p>两者必须逐字相等：一边当 number、一边当 string，前端拿到的类型就会不一致。
     */
    public static final long JS_SAFE_INTEGER = 9007199254740991L;
}
