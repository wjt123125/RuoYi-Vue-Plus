package org.dromara.databus.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 监控大盘概览卡片（当前窗口 + 上一等长窗口，供前端算环比）。
 * <p>
 * 两个窗口口径完全一致、首尾相接：当前窗口 [begin, end)，上一窗口 [begin - span, begin)。
 * 环比（增长/下降）由前端按两窗口字段自算，后端不预设增减语义。
 *
 * @author databus
 */
@Data
public class MonitorOverviewVo {

    /**
     * 统计窗口小时数（当前与上一窗口各占同样小时数）。
     */
    private Integer hours;

    /**
     * 当前窗口起点（含）。
     */
    private LocalDateTime begin;

    /**
     * 当前窗口终点（不含，即当前整点）。
     */
    private LocalDateTime end;

    /**
     * 当前窗口总执行次数。
     */
    private Integer totalCount;

    /**
     * 当前窗口成功次数。
     */
    private Integer successCount;

    /**
     * 当前窗口失败次数。
     */
    private Integer failedCount;

    /**
     * 当前窗口成功率（百分比，保留两位）；无样本时为 null。
     */
    private BigDecimal successRate;

    /**
     * 当前窗口加权平均耗时（毫秒）；无样本时为 null。
     */
    private Long avgDuration;

    /**
     * 当前窗口 P95 耗时（毫秒）。小时桶分位不可跨桶加权，取窗口内各链路-小时桶的最劣值；无样本时为 null。
     */
    private Long p95Duration;

    /**
     * 当前窗口 P99 耗时（毫秒，口径同 P95）；无样本时为 null。
     */
    private Long p99Duration;

    /**
     * 当前窗口有执行的链路数（活跃链路）。
     */
    private Integer activeChainCount;

    /**
     * 当前处于 FIRING 状态的告警数（时点值，非窗口统计，无环比）。
     */
    private Long firingAlertCount;

    /**
     * 上一窗口总执行次数。
     */
    private Integer prevTotalCount;

    /**
     * 上一窗口成功次数。
     */
    private Integer prevSuccessCount;

    /**
     * 上一窗口失败次数。
     */
    private Integer prevFailedCount;

    /**
     * 上一窗口成功率（百分比，保留两位）；无样本时为 null。
     */
    private BigDecimal prevSuccessRate;

    /**
     * 上一窗口加权平均耗时（毫秒）；无样本时为 null。
     */
    private Long prevAvgDuration;

    /**
     * 上一窗口 P95 耗时（毫秒，口径同当前窗口）；无样本时为 null。
     */
    private Long prevP95Duration;

    /**
     * 上一窗口 P99 耗时（毫秒）；无样本时为 null。
     */
    private Long prevP99Duration;

    /**
     * 上一窗口有执行的链路数。
     */
    private Integer prevActiveChainCount;

}