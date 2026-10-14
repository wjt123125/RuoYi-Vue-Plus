package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 错误指标小时聚合表对象 databus_metric_error_hourly
 * <p>
 * 一行 = 一条链路一类错误指纹一小时。指纹为归一化后的错误摘要（取首行、去数字/id、截断），
 * 同指纹合并计数；error_sample 留原始首段便于排查。高频错误 Top 排行数据源。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_metric_error_hourly")
public class DatabusMetricErrorHourly extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 链路编码
     */
    private String chainCode;

    /**
     * 统计小时桶（整点）
     */
    private LocalDateTime statHour;

    /**
     * 错误指纹（归一化摘要，去重维度）
     */
    private String errorFingerprint;

    /**
     * 错误样例（原始错误信息首段）
     */
    private String errorSample;

    /**
     * 发生次数
     */
    private Integer happenCount;

}