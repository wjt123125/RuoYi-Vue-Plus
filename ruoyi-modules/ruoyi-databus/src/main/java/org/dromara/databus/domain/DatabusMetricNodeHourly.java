package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 节点指标小时聚合表对象 databus_metric_node_hourly
 * <p>
 * 一行 = 一条链路的一个组件实例一小时。数据源为 databus_execution_node（FULL 档明细，
 * 经 execution_id 关联 databus_execution 取 chain_code 与小时桶），慢组件 Top 排行数据源。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_metric_node_hourly")
public class DatabusMetricNodeHourly extends BaseEntity {

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
     * 节点 tag（组件实例标识/数据空间名）
     */
    private String tag;

    /**
     * 组件注册类型名（httpRequest/condition 等）
     */
    private String nodeType;

    /**
     * 统计小时桶（整点）
     */
    private LocalDateTime statHour;

    /**
     * 调用次数
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
     * 平均耗时（毫秒）
     */
    private Long avgDuration;

    /**
     * 最大耗时（毫秒）
     */
    private Long maxDuration;

}