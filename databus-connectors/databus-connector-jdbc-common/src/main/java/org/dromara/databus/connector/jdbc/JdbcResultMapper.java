package org.dromara.databus.connector.jdbc;

import org.dromara.databus.connector.ConnectorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ResultSet → JSON 类型矩阵（方案 §7.1）+ 强制护栏（§7.2）。
 *
 * <p>三条不能松的约定：
 * <ul>
 *   <li><b>NULL 就是 {@code null}，不转空串</b>——老系统 RdsConfigProcessor 的教训之一：
 *       空串与 null 在下游条件判断里语义完全不同，转了就是静默改数据；</li>
 *   <li><b>TIMESTAMP 落 {@code yyyy-MM-dd HH:mm:ss}</b>，不用 Jackson 默认的 ISO-8601（带 T/Z），
 *       否则与 BPM 侧组件产出格式不一致，下游字符串比对会断；</li>
 *   <li><b>BIGINT 超过 JS 安全整数上限（2^53）转 String</b>——雪花 ID 必踩，
 *       前端拿到 {@code 1234567890123456789} 会变成 {@code 1234567890123456800}。</li>
 * </ul>
 *
 * <p>列名用 {@code getColumnLabel}（不是 {@code getColumnName}，否则 {@code AS} 别名失效），
 * 再按 {@code columnNameCase} 归一——Oracle、达梦默认返回大写列名，不归一的话换库即断链路。
 *
 * <p>BLOB / CLOB 单字段截断 1MB，超限置 {@code "<truncated:N bytes>"} + warn（§7.2）。
 *
 * @author databus
 */
public final class JdbcResultMapper {

    private static final Logger log = LoggerFactory.getLogger(JdbcResultMapper.class);

    /** 单个 BLOB/CLOB 字段的截断上限：1MB（§7.2） */
    private static final int MAX_LOB_BYTES = 1024 * 1024;

    /** JS Number 安全整数上限 2^53（§7.1 BIGINT 行） */
    private static final long JS_SAFE_INTEGER = 9007199254740992L;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 标量类型白名单（§3.3 / §8.1 dbQuery 的 scalarType 下拉） */
    private static final List<String> SCALAR_TYPES = List.of("string", "int", "long", "double");

    private JdbcResultMapper() {
    }

    /**
     * 读全部行。调用方需已 {@code setMaxRows}，本方法不再自行截断（护栏落在 Statement 上，
     * 让驱动少传数据，比读出来再丢省网络）。
     *
     * @param rs   结果集（调用方负责关闭）
     * @param cfg  连接配置（提供 columnNameCase / decimalAsString）
     * @param sink 行收集器；返回 false 表示停止读取
     * @return 列名清单（已按 columnNameCase 归一）
     */
    public static List<String> readRows(ResultSet rs, JdbcConnectionCfg cfg, RowSink sink) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        int[] sqlTypes = new int[columnCount + 1];
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            sqlTypes[i] = meta.getColumnType(i);
            columns.add(normalizeColumn(meta.getColumnLabel(i), cfg.columnNameCase()));
        }
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(columnCount * 2);
            for (int i = 1; i <= columnCount; i++) {
                row.put(columns.get(i - 1), readColumn(rs, i, sqlTypes[i], cfg));
            }
            if (!sink.accept(row)) {
                break;
            }
        }
        return columns;
    }

    /**
     * 标量取值：第一行第一列，按 scalarType 转换。
     *
     * @param scalarType string / int / long / double；留空或非法值按 string
     */
    public static Object readScalar(ResultSet rs, int sqlType, JdbcConnectionCfg cfg, String scalarType)
        throws SQLException {
        if (!rs.next()) {
            return null;
        }
        Object raw = readColumn(rs, 1, sqlType, cfg);
        return convertScalar(raw, scalarType);
    }

    /**
     * 单列读取：按 JDBC 类型走 §7.1 矩阵，未知类型兜底 {@code String.valueOf(rs.getObject(i))}（不抛错）。
     *
     * <p>public 是因为方言差异（如 pg 的 {@code jsonb}、Oracle 的 {@code ROWID}）可能需要子类覆写；
     * 兜底分支保证覆写者漏了某个类型也不会整条链路失败。
     */
    public static Object readColumn(ResultSet rs, int index, int sqlType, JdbcConnectionCfg cfg) throws SQLException {
        switch (sqlType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR:
                return rs.getString(index);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER: {
                int v = rs.getInt(index);
                return rs.wasNull() ? null : v;
            }
            case Types.BIGINT: {
                long v = rs.getLong(index);
                if (rs.wasNull()) {
                    return null;
                }
                // 超出 JS 安全整数范围转字符串：前端 JSON.parse 后不会丢精度
                return Math.abs(v) > JS_SAFE_INTEGER ? String.valueOf(v) : v;
            }
            case Types.REAL, Types.FLOAT, Types.DOUBLE: {
                double v = rs.getDouble(index);
                return rs.wasNull() ? null : v;
            }
            case Types.NUMERIC, Types.DECIMAL: {
                BigDecimal bd = rs.getBigDecimal(index);
                if (bd == null) {
                    return null;
                }
                return cfg.decimalAsString() ? bd.toPlainString() : bd;
            }
            case Types.DATE: {
                java.sql.Date d = rs.getDate(index);
                return d == null ? null : d.toLocalDate().format(DATE_FMT);
            }
            case Types.TIME, Types.TIME_WITH_TIMEZONE: {
                java.sql.Time t = rs.getTime(index);
                return t == null ? null : t.toLocalTime().format(TIME_FMT);
            }
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE: {
                java.sql.Timestamp ts = rs.getTimestamp(index);
                return ts == null ? null : ts.toLocalDateTime().format(TIMESTAMP_FMT);
            }
            case Types.BOOLEAN, Types.BIT: {
                boolean b = rs.getBoolean(index);
                return rs.wasNull() ? null : b;
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB:
                return readBinary(rs, index);
            case Types.CLOB, Types.NCLOB:
                return readClob(rs, index);
            default: {
                Object o = rs.getObject(index);
                return o == null ? null : String.valueOf(o);
            }
        }
    }

    /**
     * 标量类型转换（{@code resultMode=scalar} 时用）。null 原样返回——
     * 查不到行与「查到一行值是 null」在下游都该是 null，不该变成 0 或 "null"。
     */
    public static Object convertScalar(Object value, String scalarType) {
        if (value == null) {
            return null;
        }
        String type = scalarType == null || scalarType.isBlank() ? "string" : scalarType.trim().toLowerCase();
        if (!SCALAR_TYPES.contains(type)) {
            throw new ConnectorException("JDBC_SCALAR_TYPE_INVALID",
                "scalarType 取值不合法（string / int / long / double）：" + scalarType);
        }
        try {
            return switch (type) {
                case "int" -> value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value).trim());
                case "long" -> value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value).trim());
                case "double" -> value instanceof Number n ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(value).trim());
                default -> String.valueOf(value);
            };
        } catch (NumberFormatException e) {
            throw new ConnectorException("JDBC_SCALAR_CONVERT_FAILED",
                "标量值无法转为 " + type + "（查出的值不是数字）", e);
        }
    }

    /** 列名归一：AS_IS 原样 / LOWER 全小写 / UPPER 全大写；null 列名兜底成 column{index} 由调用方处理 */
    public static String normalizeColumn(String label, String columnNameCase) {
        if (label == null) {
            return null;
        }
        return switch (columnNameCase == null ? "AS_IS" : columnNameCase) {
            case "LOWER" -> label.toLowerCase();
            case "UPPER" -> label.toUpperCase();
            default -> label;
        };
    }

    // ---------------------------- 私有实现 ----------------------------

    /** 二进制 → Base64 字符串；超 1MB 截断并置标记（§7.1 / §7.2） */
    private static Object readBinary(ResultSet rs, int index) throws SQLException {
        byte[] bytes = rs.getBytes(index);
        if (bytes == null) {
            return null;
        }
        if (bytes.length > MAX_LOB_BYTES) {
            log.warn("[db] 二进制字段超 1MB 已截断：第 {} 列，原始 {} 字节", index, bytes.length);
            return "<truncated:" + bytes.length + " bytes>";
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** CLOB → 字符串；超 1MB 截断并置标记。读完立即 free，避免长事务下驱动侧 LOB 句柄堆积 */
    private static Object readClob(ResultSet rs, int index) throws SQLException {
        Clob clob = rs.getClob(index);
        if (clob == null) {
            return null;
        }
        try {
            long length = clob.length();
            if (length == 0) {
                return "";
            }
            if (length > MAX_LOB_BYTES) {
                log.warn("[db] CLOB 字段超 1MB 已截断：第 {} 列，原始 {} 字符", index, length);
                return "<truncated:" + length + " bytes>";
            }
            return clob.getSubString(1, (int) length);
        } finally {
            try {
                clob.free();
            } catch (SQLException | UnsupportedOperationException e) {
                // 部分驱动未实现 free()（抛 UnsupportedOperationException），不影响结果
                log.debug("[db] Clob.free() 不可用：{}", e.toString());
            }
        }
    }

    /**
     * 行收集器：{@link #readRows} 的回调，让「读全部行 / 只读第一行」共用一套列解析逻辑。
     */
    @FunctionalInterface
    public interface RowSink {

        /**
         * @param row 已按类型矩阵转换好的一行
         * @return 是否继续读取下一行（只要首行时返回 false，驱动侧可少传数据）
         */
        boolean accept(Map<String, Object> row);
    }
}
