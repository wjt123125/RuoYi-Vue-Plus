package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * RDS_EXECUTE 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.RdsExecuteRequest} 字段一致）。
 * <p>
 * 对应老系统 RdsConfigProcessor；老系统 SqlValueProcessor 的规则引擎 @sqlValue 标量取数
 * 并入本端点的标量方法（无状态端点无流程实例上下文，不能调 executeAtScript）。
 *
 * @author databus
 */
@Data
public class RdsExecuteRequest {

    /** BPM 后台注册的 RDS 数据源 ID（必填） */
    private String rdsId;

    /** 执行方法：getString/getInt/getLong/getDouble/getMap/getMaps/update/batch（必填） */
    private String method;

    /** SQL：单条方法为字符串；batch 多 SQL 模式为字符串数组 */
    private Object sql;

    /** 参数：普通方法为数组/单值；batch 批量参数模式为数组的数组 */
    private Object args;

    /** 抓取大小，可选。大于 0 时生效 */
    private Integer fetchSize;

    /** 最大行数，可选。大于 0 时生效 */
    private Integer maxRows;
}
