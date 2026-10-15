package org.dromara.databus.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 链路健康行：窗口内每个有执行的链路一行的量质速度全景，大盘「链路健康」区块消费。
 * P95 为窗口内各小时桶的最劣值（小时桶分位不可跨桶加权合并，见宽表口径）。
 *
 * @author databus
 */
@Data
public class ChainHealthVo {

    /**
     * 链路编码。
     */
    private String chainCode;

    /**
     * 窗口内总执行次数。
     */
    private Integer totalCount;

    /**
     * 窗口内成功次数。
     */
    private Integer successCount;

    /**
     * 窗口内失败次数。
     */
    private Integer failedCount;

    /**
     * 窗口内成功率（百分比，保留两位）；无样本时为 null。
     */
    private BigDecimal successRate;

    /**
     * 窗口内加权平均耗时（毫秒）；无样本时为 null。
     */
    private Long avgDuration;

    /**
     * 窗口内 P95 耗时（毫秒，各小时桶最劣值）；无样本时为 null。
     */
    private Long p95Duration;

}
