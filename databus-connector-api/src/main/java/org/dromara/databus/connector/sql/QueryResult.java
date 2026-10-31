package org.dromara.databus.connector.sql;

import java.util.List;
import java.util.Map;

/**
 * SQL 查询结果（方案 §1.4.1 / §8.1）。按 resultMode 只有一处有值，其余为 null：
 * rows（rows 模式）/ row（first 模式）/ value（scalar 模式）；rowCount 与 columns 始终有值。
 *
 * @param rows     多行结果（List&lt;Map&lt;列名, 值&gt;&gt;），resultMode=rows 时有值
 * @param row      首行结果（Map），resultMode=first 时有值
 * @param value    标量结果，resultMode=scalar 时有值
 * @param rowCount 命中的行数（不受 maxRows 截断影响）
 * @param columns  列名（ResultSetMetaData.getColumnLabel 按 columnNameCase 归一）
 * @author databus
 */
public record QueryResult(List<Map<String, Object>> rows,
                          Map<String, Object> row,
                          Object value,
                          int rowCount,
                          List<String> columns) {
}