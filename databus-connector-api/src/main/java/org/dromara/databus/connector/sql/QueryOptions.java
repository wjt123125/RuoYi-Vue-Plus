package org.dromara.databus.connector.sql;

/**
 * SQL 查询选项（方案 §1.4.1 / §8.1）。
 *
 * <p><b>不含 resultMode</b>：结果形态由调哪个方法决定（{@code queryForList} / {@code queryForMap} /
 * {@code queryForObject}），不再靠一个字段在联合体 record 里挑分支（§3.4）。
 *
 * @param scalarType     标量类型：string / int / long / double，仅 {@code queryForObject} 生效
 * @param maxRows        最大返回行数（平台硬上限 10000）
 * @param fetchSize      JDBC fetch size（默认 100）
 * @param columnNameCase 列名大小写归一：AS_IS / LOWER / UPPER（Oracle、达梦默认大写列名）
 * @author databus
 */
public record QueryOptions(String scalarType,
                           Integer maxRows,
                           Integer fetchSize,
                           String columnNameCase) {
}
