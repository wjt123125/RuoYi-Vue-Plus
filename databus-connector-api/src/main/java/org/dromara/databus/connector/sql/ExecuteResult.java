package org.dromara.databus.connector.sql;

import java.util.List;

/**
 * SQL 执行结果（方案 §1.4.1 / §8.1）。
 *
 * @param affectedRows  受影响行数
 * @param generatedKeys 生成的主键（returnGeneratedKeys=true 时回填，否则 null）
 * @author databus
 */
public record ExecuteResult(int affectedRows, List<Object> generatedKeys) {
}