package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 链路执行节点记录（节点级明细）对象 databus_execution_node
 * <p>
 * 仅 FULL 档写入：NodeStepResultCollector 合并演化后把每个节点的状态/耗时/异常/数据空间快照
 * 追加进追踪牌缓冲（线程安全），流程结束后随总账尽力批量插入。
 * 字段来源见设计文档 §4.2——标识取 CmpStep（nodeInstanceId 区分循环多轮），
 * 产出取 {@code context.snapshotDataSpace(tag)} 当场快照（slot.getInput/getOutput 普通组件恒空）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_execution_node")
public class DatabusExecutionNode extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 执行记录id（databus_execution.id）
     */
    private Long executionId;

    /**
     * 节点实例id（取 CmpStep.refNode.nodeInstanceId，需开 liteflow.enable-node-instance-id；
     * 区分同一 nodeId 在链中的多次出现，循环多轮另靠 branch_info 的 LOOP 轮次）
     */
    private String nodeInstanceId;

    /**
     * 节点 tag（组件实例标识/数据空间名）
     */
    private String tag;

    /**
     * 组件注册类型名（httpRequest/condition/forLoop 等）
     */
    private String nodeType;

    /**
     * 节点执行前数据树快照（可缺省）
     */
    private String inputJson;

    /**
     * 节点执行后 $.<tag> 数据空间快照（布尔组件记判定结果）
     */
    private String outputJson;

    /**
     * 节点状态（SUCCESS成功/FAILED失败）
     */
    private String status;

    /**
     * 失败时错误信息
     */
    private String errorMsg;

    /**
     * 节点开始时间（CmpStep.startTime）
     */
    private LocalDateTime startTime;

    /**
     * 节点结束时间（CmpStep.endTime）
     */
    private LocalDateTime endTime;

    /**
     * 节点耗时（毫秒，CmpStep.timeSpent）
     */
    private Long duration;

    /**
     * 分支标记（IF 真假/SWITCH 命中 case/循环轮次）
     */
    private String branchInfo;

}
