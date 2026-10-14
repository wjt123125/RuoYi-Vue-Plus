package org.dromara.databus.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 指标小时聚合结果（定时任务日志与手动端点返回共用）。
 *
 * @author databus
 */
@Data
public class MetricAggregateVo {

    /**
     * 本次聚合的目标小时桶（整点）。
     */
    private LocalDateTime statHour;

    /**
     * 写入的链路级聚合行数（databus_metric_hourly）。
     */
    private Integer chainRows;

    /**
     * 写入的节点级聚合行数（databus_metric_node_hourly）。
     */
    private Integer nodeRows;

    /**
     * 写入的错误聚合行数（databus_metric_error_hourly）。
     */
    private Integer errorRows;

    /**
     * 聚合耗时（毫秒）。
     */
    private Long costMillis;

}