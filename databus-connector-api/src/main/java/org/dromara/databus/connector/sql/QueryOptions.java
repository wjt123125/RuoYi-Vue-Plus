package org.dromara.databus.connector.sql;

/**
 * SQL 查询选项（方案 §1.4.1 / §8.1）。
 *
 * @param resultMode     结果模式：rows（多行）/ first（首行）/ scalar（单值）
 * @param scalarType     scalar 模式下的标量类型：string / int / long / double（仅 resultMode=scalar 生效）
 * @param maxRows        最大返回行数（平台硬上限 10000）
 * @param fetchSize      JDBC fetch size（默认 100）
 * @param columnNameCase 列名大小写归一：AS_IS / LOWER / UPPER（Oracle、达梦默认大写列名）
 * @author databus
 */
public record QueryOptions(String resultMode,
                           String scalarType,
                           Integer maxRows,
                           Integer fetchSize,
                           String columnNameCase) {
}