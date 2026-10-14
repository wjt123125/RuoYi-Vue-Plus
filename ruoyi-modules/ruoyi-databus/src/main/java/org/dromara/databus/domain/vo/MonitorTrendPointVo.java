package org.dromara.databus.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监控趋势时间序列点（大盘折线图按小时桶返回一个点）。
 * <p>
 * 由 databus_metric_hourly 按 stat_hour 汇总得出：多链路在同一小时的行求和，
 * 成功率与平均耗时为全链路合并口径（非各链路简单平均）。
 *
 * @author databus
 */
@Data
public class MonitorTrendPointVo {

    /**
     * 小时桶（整点）。
     */
    private LocalDateTime statHour;

    /**
     * 该小时总执行次数（全链路合计）。
     */
    private Integer totalCount;

    /**
     * 该小时成功次数。
     */
    private Integer successCount;

    /**
     * 该小时失败次数。
     */
    private Integer failedCount;

    /**
     * 该小时成功率（百分比，保留两位）；无样本时为 null。
     */
    private BigDecimal successRate;

    /**
     * 该小时加权平均耗时（毫秒，按执行次数加权）；无样本时为 null。
     */
    private Long avgDuration;

}