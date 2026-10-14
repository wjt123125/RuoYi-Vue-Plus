package org.dromara.databus.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 链路失败排行榜行（窗口内按失败次数倒序）。
 *
 * @author databus
 */
@Data
public class ChainFailureRankVo {

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

}