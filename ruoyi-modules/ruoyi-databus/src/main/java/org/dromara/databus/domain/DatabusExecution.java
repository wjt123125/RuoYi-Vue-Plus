package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 链路执行记录（执行级总账）对象 databus_execution
 * <p>
 * 只增不改的审计表：无逻辑删除字段，过期数据由保留期清理任务物理删除
 * （Spring @Scheduled，默认保留 30 天；手动端点 /databus/execution/cleanup）。
 * 采集口径见设计文档 §4.3——execute() 挂追踪牌，流程结束后 PostProcessFlowExecuteLifeCycle
 * 一次性落库；OFF 档不产生记录，BASIC 只写总账，FULL 另写节点明细。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_execution")
public class DatabusExecution extends BaseEntity {

    /**
     * 执行记录id（executionId）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 链路id（链路查不到的失败记录为空）
     */
    private Long chainId;

    /**
     * 链路编码（冗余，列表查询/重跑定位用）
     */
    private String chainCode;

    /**
     * 执行入参 JSON（数据树根，重跑据此还原）
     */
    private String requestData;

    /**
     * 最终输出 JSON（执行结束时数据树快照）
     */
    private String responseData;

    /**
     * 执行状态（RUNNING进行中/SUCCESS成功/FAILED失败）
     */
    private String status;

    /**
     * 失败时错误信息
     */
    private String errorMsg;

    /**
     * 执行开始时间
     */
    private LocalDateTime startTime;

    /**
     * 执行结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Long duration;

}
