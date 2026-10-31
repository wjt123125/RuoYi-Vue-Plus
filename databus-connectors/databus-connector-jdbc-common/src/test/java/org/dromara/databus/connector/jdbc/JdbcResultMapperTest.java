package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcResultMapper} 的 §7.1 类型矩阵 + §7.2 护栏单元测试（方案 §5.4：连接器模块可单独
 * {@code mvn test}，不启动平台）。
 *
 * <p><b>为什么用 H2</b>：矩阵的坑几乎全在「真实驱动怎么报 sqlType、怎么答 wasNull、Clob 能不能 free」，
 * 拿自己写的假 ResultSet 去验等于把假设抄两遍。H2 是一个货真价实的 JDBC 驱动，
 * 只作 test scope（不进 zip，不违 §2.2 打包红线；也不违 §9.1 驱动白名单——那条管的是运行时可连库种）。
 *
 * <p><b>为什么还留了几个桩</b>：NCHAR / NVARCHAR / LONGNVARCHAR / NCLOB / BIT / FLOAT /
 * TIME_WITH_TIMEZONE 与「未知类型兜底」这些分支 H2 表达不出来（它没有国家字符集类型，
 * FLOAT 直接归一成 DOUBLE PRECISION）。这几行走 JDK 动态代理造的单列桩结果集，
 * 不用 Mockito 是为了不给依赖刻意极简的连接器模块再加一个 test 依赖（§2.1.2）。
 *
 * @author databus
 */
@Tag("dev")
class JdbcResultMapperTest {

    /** §7.2 的 BLOB/CLOB 单字段截断上限，与被测常量同值 */
    private static final int LOB_LIMIT = 1024 * 1024;

    /** JS Number 安全整数上限 2^53，与被测常量同值 */
    private static final long JS_SAFE_INTEGER = 9007199254740992L;

    /** 每个用例一个独立内存库（不给 DB_CLOSE_DELAY，关连接即销毁，用例间零残留） */
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private Connection conn;

    @BeforeEach
    void open() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:mapper" + DB_SEQ.incrementAndGet(), "sa", "");
    }

    @AfterEach
    void close() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    // ---------------------------- §7.1 类型矩阵（H2 真实驱动） ----------------------------

    @Test
    void 全类型一行按矩阵落到JSON标量() throws SQLException {
        createMatrixTable();
        execute("""
            INSERT INTO matrix_t VALUES (
              'abcd', 'hello', 7, 8, 9, %d, 1.5, 2.5, 12345678901234567890.1234,
              DATE '2026-10-30', TIME '13:45:59', TIMESTAMP '2026-10-30 13:45:59',
              TIMESTAMP WITH TIME ZONE '2026-10-30 13:45:59+08:00',
              TRUE, X'DEADBEEF', X'0102', 'clob-text')
            """.formatted(JS_SAFE_INTEGER + 1));

        Map<String, Object> row = queryOne("SELECT * FROM matrix_t", cfg("LOWER", true));

        // 字符类：原样 String，不做 trim / 不转义
        assertEquals("abcd", row.get("c_char"), "CHAR 应原样返回（含定长语义由驱动负责）");
        assertEquals("hello", row.get("c_varchar"));

        // 整数类：TINYINT / SMALLINT / INTEGER 一律 Integer（不是 Long，前端 JSON 数字即可）
        assertEquals(7, row.get("c_tinyint"));
        assertEquals(8, row.get("c_smallint"));
        assertEquals(9, row.get("c_int"));
        assertInstanceOf(Integer.class, row.get("c_int"));

        // BIGINT 超 2^53 转 String：雪花 ID 必踩，转数字前端 JSON.parse 会丢精度
        assertEquals(String.valueOf(JS_SAFE_INTEGER + 1), row.get("c_bigint"),
            "BIGINT 超出 JS 安全整数必须转字符串");

        // 浮点类：REAL / DOUBLE 一律 Double
        assertEquals(1.5d, row.get("c_real"));
        assertEquals(2.5d, row.get("c_double"));

        // NUMERIC/DECIMAL 默认转 String（decimalAsString=true），防精度丢失
        assertEquals("12345678901234567890.1234", row.get("c_decimal"),
            "DECIMAL 默认应输出 toPlainString（不带科学计数法）");

        // 时间类：一律格式化字符串，绝不用 Jackson 默认的 ISO-8601
        assertEquals("2026-10-30", row.get("c_date"));
        assertEquals("13:45:59", row.get("c_time"));
        assertEquals("2026-10-30 13:45:59", row.get("c_timestamp"), "TIMESTAMP 不能带 T / Z");
        assertInstanceOf(String.class, row.get("c_tstz"));
        assertTrue(String.valueOf(row.get("c_tstz")).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
            "TIMESTAMP WITH TIME ZONE 走同一个 yyyy-MM-dd HH:mm:ss 格式，实际值取决于 JVM 时区故只验形状");

        assertEquals(Boolean.TRUE, row.get("c_boolean"));

        // 二进制：Base64
        assertEquals(Base64.getEncoder().encodeToString(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}),
            row.get("c_binary"));
        assertEquals("AQI=", row.get("c_varbinary"));

        assertEquals("clob-text", row.get("c_clob"));
    }

    @Test
    void decimalAsString关掉时输出BigDecimal() throws SQLException {
        createMatrixTable();
        execute("INSERT INTO matrix_t (c_decimal) VALUES (1.20)");

        Map<String, Object> row = queryOne("SELECT c_decimal FROM matrix_t", cfg("LOWER", false));

        assertEquals(new BigDecimal("1.2000"), row.get("c_decimal"),
            "decimalAsString=false 时交回 BigDecimal，且 scale 跟列定义走（不做归一）");
    }

    @Test
    void 整行NULL保持null不转空串() throws SQLException {
        createMatrixTable();
        execute("INSERT INTO matrix_t DEFAULT VALUES");

        Map<String, Object> row = queryOne("SELECT * FROM matrix_t", cfg("LOWER", true));

        // 这条是矩阵里最容易被"顺手兜底"改坏的一格：空串与 null 在下游条件判断里语义完全不同
        for (String column : List.of("c_char", "c_varchar", "c_tinyint", "c_smallint", "c_int", "c_bigint",
            "c_real", "c_double", "c_decimal", "c_date", "c_time", "c_timestamp", "c_tstz", "c_boolean",
            "c_binary", "c_varbinary", "c_clob")) {
            assertTrue(row.containsKey(column), "列应存在：" + column);
            assertNull(row.get(column), "NULL 必须落成 null 而非空串/0：" + column);
        }
    }

    @Test
    void 大整数边界刚好等于上限时仍是数字() throws SQLException {
        createMatrixTable();
        execute("INSERT INTO matrix_t (c_bigint) VALUES (%d), (%d), (%d)"
            .formatted(JS_SAFE_INTEGER, -JS_SAFE_INTEGER, JS_SAFE_INTEGER + 1));

        List<Map<String, Object>> rows = queryAll("SELECT c_bigint FROM matrix_t ORDER BY c_bigint DESC",
            cfg("LOWER", true));

        assertEquals(String.valueOf(JS_SAFE_INTEGER + 1), rows.get(0).get("c_bigint"), "超上限转 String");
        assertEquals(JS_SAFE_INTEGER, rows.get(1).get("c_bigint"), "刚好等于上限仍是 Long（判定是 > 不是 >=）");
        assertEquals(-JS_SAFE_INTEGER, rows.get(2).get("c_bigint"), "负向同理，取绝对值比较");
    }

    @Test
    void 二进制超1MB截断为标记串() throws SQLException {
        execute("CREATE TABLE lob_t (b BLOB, c CLOB)");
        byte[] bigBytes = new byte[LOB_LIMIT + 1];
        Arrays.fill(bigBytes, (byte) 'x');
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO lob_t (b, c) VALUES (?, ?)")) {
            ps.setBytes(1, bigBytes);
            ps.setString(2, "y".repeat(LOB_LIMIT + 1));
            ps.executeUpdate();
        }

        Map<String, Object> row = queryOne("SELECT b, c FROM lob_t", cfg("LOWER", true));

        // §7.2：截断而不是抛错——一张表里塞了个大附件不该让整条链路失败
        assertEquals("<truncated:" + (LOB_LIMIT + 1) + " bytes>", row.get("b"), "BLOB 超限应截断并回显原始字节数");
        assertEquals("<truncated:" + (LOB_LIMIT + 1) + " bytes>", row.get("c"), "CLOB 超限同理（按字符数计）");
    }

    @Test
    void 二进制刚好1MB不截断() throws SQLException {
        execute("CREATE TABLE lob_edge (b BLOB)");
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO lob_edge (b) VALUES (?)")) {
            ps.setBytes(1, new byte[LOB_LIMIT]);
            ps.executeUpdate();
        }

        Map<String, Object> row = queryOne("SELECT b FROM lob_edge", cfg("LOWER", true));

        String base64 = assertInstanceOf(String.class, row.get("b"));
        assertFalse(base64.startsWith("<truncated:"), "等于上限不算超限（判定是 > 不是 >=）");
        assertEquals(LOB_LIMIT, Base64.getDecoder().decode(base64).length);
    }

    // ---------------------------- 列名归一 ----------------------------

    @Test
    void 列名取别名并按columnNameCase归一() throws SQLException {
        String sql = "SELECT 1 AS \"UserId\", 'x' AS \"MixedCase\"";

        assertEquals(List.of("UserId", "MixedCase"), columns(sql, cfg("AS_IS", true)));
        assertEquals(List.of("userid", "mixedcase"), columns(sql, cfg("LOWER", true)));
        assertEquals(List.of("USERID", "MIXEDCASE"), columns(sql, cfg("UPPER", true)));
    }

    @Test
    void 未加引号的列名由驱动大写归一后可被LOWER拉回小写() throws SQLException {
        createMatrixTable();

        // H2 与 Oracle / 达梦同款行为：未加引号的标识符落成大写。不归一的话换库即断链路
        assertEquals(List.of("C_VARCHAR"), columns("SELECT c_varchar FROM matrix_t", cfg("AS_IS", true)));
        assertEquals(List.of("c_varchar"), columns("SELECT c_varchar FROM matrix_t", cfg("LOWER", true)));
    }

    @Test
    void normalizeColumn对null与非法档位的行为() {
        assertNull(JdbcResultMapper.normalizeColumn(null, "LOWER"), "null 列名原样返回，由调用方兜底");
        assertEquals("AbC", JdbcResultMapper.normalizeColumn("AbC", null), "columnNameCase 缺省按 AS_IS");
        assertEquals("AbC", JdbcResultMapper.normalizeColumn("AbC", "camel"), "非法档位退回 AS_IS，不抛错");
    }

    // ---------------------------- readRows / readScalar ----------------------------

    @Test
    void sink返回false即停止读取() throws SQLException {
        createMatrixTable();
        execute("INSERT INTO matrix_t (c_varchar) VALUES ('a'), ('b'), ('c')");

        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            JdbcResultMapper.readRows(st.executeQuery("SELECT c_varchar FROM matrix_t ORDER BY c_varchar"),
                cfg("LOWER", true), row -> {
                    rows.add(row);
                    return false;
                });
        }

        assertEquals(1, rows.size(), "只要首行时不该把整张表拉回来");
        assertEquals("a", rows.get(0).get("c_varchar"));
    }

    @Test
    void readScalar空结果集返回null() throws SQLException {
        createMatrixTable();

        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT c_varchar FROM matrix_t");
            assertNull(JdbcResultMapper.readScalar(rs, Types.VARCHAR, cfg("LOWER", true), "string"),
                "查不到行是 null，不是空串也不是 0");
        }
    }

    @Test
    void readScalar按scalarType收敛类型() throws SQLException {
        createMatrixTable();
        execute("INSERT INTO matrix_t (c_varchar) VALUES ('42')");

        assertEquals(42, scalar("c_varchar", Types.VARCHAR, "int"));
        assertEquals(42L, scalar("c_varchar", Types.VARCHAR, "long"));
        assertEquals(42.0d, scalar("c_varchar", Types.VARCHAR, "double"));
        assertEquals("42", scalar("c_varchar", Types.VARCHAR, "string"));
        assertEquals("42", scalar("c_varchar", Types.VARCHAR, "  "), "留空按 string");
    }

    @Test
    void convertScalar的两类失败都给明确错误码() {
        ConnectorException badType = assertThrows(ConnectorException.class,
            () -> JdbcResultMapper.convertScalar("1", "bigint"));
        assertEquals("JDBC_SCALAR_TYPE_INVALID", badType.getCode());

        ConnectorException badValue = assertThrows(ConnectorException.class,
            () -> JdbcResultMapper.convertScalar("abc", "int"));
        assertEquals("JDBC_SCALAR_CONVERT_FAILED", badValue.getCode());

        assertNull(JdbcResultMapper.convertScalar(null, "int"), "null 原样返回，不该变成 0");
    }

    // ---------------------------- H2 表达不出来的分支（单列桩） ----------------------------

    @Test
    void 字符类四个别名类型都走getString() throws SQLException {
        for (int sqlType : new int[]{Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR}) {
            assertEquals("国", JdbcResultMapper.readColumn(stubColumn(sqlType, "国"), 1, sqlType, cfg("AS_IS", true)),
                "sqlType=" + sqlType + " 应走 getString");
        }
    }

    @Test
    void BIT与FLOAT分别落到Boolean与Double() throws SQLException {
        assertEquals(Boolean.TRUE, JdbcResultMapper.readColumn(stubColumn(Types.BIT, true), 1, Types.BIT,
            cfg("AS_IS", true)), "部分驱动用 BIT 表示布尔位");
        assertEquals(3.25d, JdbcResultMapper.readColumn(stubColumn(Types.FLOAT, 3.25f), 1, Types.FLOAT,
            cfg("AS_IS", true)));
    }

    @Test
    void 带时区的TIME与TIMESTAMP走不带时区的同一格式() throws SQLException {
        JdbcConnectionCfg asIs = cfg("AS_IS", true);
        assertEquals("09:08:07", JdbcResultMapper.readColumn(
            stubColumn(Types.TIME_WITH_TIMEZONE, java.sql.Time.valueOf("09:08:07")), 1,
            Types.TIME_WITH_TIMEZONE, asIs));
        assertEquals("2026-10-30 13:45:59", JdbcResultMapper.readColumn(
            stubColumn(Types.TIMESTAMP_WITH_TIMEZONE, java.sql.Timestamp.valueOf("2026-10-30 13:45:59")), 1,
            Types.TIMESTAMP_WITH_TIMEZONE, asIs));
    }

    @Test
    void NCLOB与未知类型的兜底() throws SQLException {
        JdbcConnectionCfg asIs = cfg("AS_IS", true);
        assertEquals("nclob-text", JdbcResultMapper.readColumn(
            stubColumn(Types.NCLOB, stubClob("nclob-text")), 1, Types.NCLOB, asIs));

        // 方言专属类型（pg 的 jsonb、Oracle 的 ROWID）落到 default 分支：转字符串，绝不抛错
        Object unknown = JdbcResultMapper.readColumn(stubColumn(Types.OTHER, 42), 1, Types.OTHER, asIs);
        assertEquals("42", unknown);
        assertInstanceOf(String.class, unknown);

        assertNull(JdbcResultMapper.readColumn(stubColumn(Types.OTHER, null), 1, Types.OTHER, asIs));
        assertNull(JdbcResultMapper.readColumn(stubColumn(Types.NCLOB, null), 1, Types.NCLOB, asIs));
    }

    // ---------------------------- 测试脚手架 ----------------------------

    private void createMatrixTable() throws SQLException {
        execute("""
            CREATE TABLE matrix_t (
              c_char      CHAR(4),
              c_varchar   VARCHAR(20),
              c_tinyint   TINYINT,
              c_smallint  SMALLINT,
              c_int       INT,
              c_bigint    BIGINT,
              c_real      REAL,
              c_double    DOUBLE PRECISION,
              c_decimal   DECIMAL(30,4),
              c_date      DATE,
              c_time      TIME,
              c_timestamp TIMESTAMP,
              c_tstz      TIMESTAMP WITH TIME ZONE,
              c_boolean   BOOLEAN,
              c_binary    BINARY(4),
              c_varbinary VARBINARY(64),
              c_clob      CLOB
            )""");
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private JdbcConnectionCfg cfg(String columnNameCase, boolean decimalAsString) {
        // 走真实工厂而非直构 record：顺带覆盖 from() 的默认值与档位归一，
        // 位置参数一多，直构写错一位编译器不会报错
        return JdbcConnectionCfg.from(JdbcDbType.POSTGRESQL, Map.of(
            "host", "127.0.0.1", "database", "mapper", "username", "sa",
            "columnNameCase", columnNameCase, "decimalAsString", decimalAsString));
    }

    private List<Map<String, Object>> queryAll(String sql, JdbcConnectionCfg cfg) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            JdbcResultMapper.readRows(st.executeQuery(sql), cfg, rows::add);
        }
        return rows;
    }

    private Map<String, Object> queryOne(String sql, JdbcConnectionCfg cfg) throws SQLException {
        List<Map<String, Object>> rows = queryAll(sql, cfg);
        assertEquals(1, rows.size(), "测试 SQL 应恰好一行：" + sql);
        return rows.get(0);
    }

    private List<String> columns(String sql, JdbcConnectionCfg cfg) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return JdbcResultMapper.readRows(st.executeQuery(sql), cfg, row -> false);
        }
    }

    private Object scalar(String column, int sqlType, String scalarType) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return JdbcResultMapper.readScalar(st.executeQuery("SELECT " + column + " FROM matrix_t"),
                sqlType, cfg("LOWER", true), scalarType);
        }
    }

    /**
     * 单列桩结果集：只实现矩阵会调到的那几个取值方法，其余一律抛
     * {@link UnsupportedOperationException}——桩被用到没预期的方法时要炸出来，不能默默返回 null。
     */
    private static ResultSet stubColumn(int sqlType, Object value) {
        AtomicBoolean wasNull = new AtomicBoolean();
        AtomicInteger cursor = new AtomicInteger();
        return (ResultSet) Proxy.newProxyInstance(JdbcResultMapperTest.class.getClassLoader(),
            new Class<?>[]{ResultSet.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "next" -> {
                        return cursor.getAndIncrement() == 0;
                    }
                    case "wasNull" -> {
                        return wasNull.get();
                    }
                    case "close" -> {
                        return null;
                    }
                    case "toString" -> {
                        return "stubColumn[sqlType=" + sqlType + "]";
                    }
                    case "hashCode" -> {
                        return System.identityHashCode(proxy);
                    }
                    case "equals" -> {
                        return proxy == args[0];
                    }
                    default -> {
                    }
                }
                wasNull.set(value == null);
                return switch (method.getName()) {
                    case "getString" -> value == null ? null : String.valueOf(value);
                    case "getInt" -> value == null ? 0 : ((Number) value).intValue();
                    case "getLong" -> value == null ? 0L : ((Number) value).longValue();
                    case "getDouble" -> value == null ? 0d : ((Number) value).doubleValue();
                    case "getBigDecimal" -> (BigDecimal) value;
                    case "getDate" -> (java.sql.Date) value;
                    case "getTime" -> (java.sql.Time) value;
                    case "getTimestamp" -> (java.sql.Timestamp) value;
                    case "getBoolean" -> value != null && (Boolean) value;
                    case "getBytes" -> (byte[]) value;
                    case "getClob" -> (Clob) value;
                    case "getObject" -> value;
                    default -> throw new UnsupportedOperationException("桩未实现 " + method.getName());
                };
            });
    }

    /** Clob 桩：只支持矩阵用到的 length / getSubString / free */
    private static Clob stubClob(String text) {
        return (Clob) Proxy.newProxyInstance(JdbcResultMapperTest.class.getClassLoader(),
            new Class<?>[]{Clob.class}, (proxy, method, args) -> switch (method.getName()) {
                case "length" -> (long) text.length();
                case "getSubString" -> {
                    int from = (int) (long) args[0] - 1;
                    yield text.substring(from, from + (int) args[1]);
                }
                case "free" -> null;
                case "toString" -> "stubClob";
                default -> throw new UnsupportedOperationException("桩未实现 " + method.getName());
            });
    }
}
