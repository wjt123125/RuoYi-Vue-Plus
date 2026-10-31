package org.dromara.databus.connector.sql;

import java.util.List;

/**
 * 批量执行结果（方案 §1.4.1 / §8.1）。
 *
 * @param affectedRows 每组受影响行数（与 argsList 逐组对应）
 * @param batchCount   实际执行的批次（executeBatch）次数
 * @author databus
 */
public record BatchResult(List<Integer> affectedRows, int batchCount) {
}