package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 链路指标小时聚合宽表对象 databus_metric_hourly
 * <p>
 * 一行 = 一条链路一小时。数据源为 databus_execution（执行级总账），按整点小时桶预聚合，
 * 大盘/趋势只读本表不回扫原始记录。幂等重跑由 DatabusMetricServiceImpl.aggregate 先删后插保证。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_metric_hourly")
public class DatabusMetricHourly extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 链路id（链路查不到的失败执行无此值）
     */
    private Long chainId;

    /**
     * 链路编码
     */
    private String chainCode;

    /**
     * 统计小时桶（整点，如 2026-10-12 14:00:00）
     */
    private LocalDateTime statHour;

    /**
     * 执行总次数
     */
    private Integer totalCount;

    /**
     * 成功次数
     */
    private Integer successCount;

    /**
     * 失败次数
     */
    private Integer failedCount;

    /**
     * 成功率（百分比 0-100；无样本为空）
     */
    private BigDecimal successRate;

    /**
     * 平均耗时（毫秒）
     */
    private Long avgDuration;

    /**
     * 最小耗时（毫秒）
     */
    private Long minDuration;

    /**
     * 最大耗时（毫秒）
     */
    private Long maxDuration;

    /**
     * P95 耗时（毫秒，最近秩法；样本不足取最大值）
     */
    private Long p95Duration;

    /**
     * P99 耗时（毫秒，最近秩法；样本不足取最大值）
     */
    private Long p99Duration;

}