package org.dromara.databus.executor.trace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 节点执行轨迹行（FULL 档节点明细的<b>纯数据</b>缓冲对象）。
 * <p>
 * 由 {@code NodeStepResultCollector} 在节点 after 钩子里当场填充，追加进
 * {@link ExecutionTrace} 的线程安全队列，流程结束后由落库钩子批量转成
 * databus_execution_node 实体。
 * <p>
 * 红线：只存可序列化的纯数据，<b>禁止持有 NodeComponent/Slot/CmpStep 引用</b>——
 * WHEN 并行的工作线程来自公共线程池，引用残留会拖住整条执行对象图。
 *
 * @author databus
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NodeTraceRow {

    /**
     * 节点实例id（取 CmpStep.refNode.nodeInstanceId，需开 enable-node-instance-id；
     * 区分同一 nodeId 在链中的多次出现，循环多轮另靠 branchInfo 的 LOOP 轮次）
     */
    private String nodeInstanceId;

    /**
     * 节点 tag（组件实例标识/数据空间名；tag 缺失时回退 nodeId 兜底）
     */
    private String tag;

    /**
     * 组件注册类型名（httpRequest/condition/forLoop 等）
     */
    private String nodeType;

    /**
     * 节点执行前数据树整树快照（before 钩子拍摄，after 消费）
     */
    private String inputJson;

    /**
     * 节点执行后 $.<tag> 数据空间快照（布尔组件记判定结果）
     */
    private String outputJson;

    /**
     * 节点状态（SUCCESS/FAILED）
     */
    private String status;

    /**
     * 失败时错误信息
     */
    private String errorMsg;

    /**
     * 节点开始时间
     */
    private Date startTime;

    /**
     * 节点结束时间
     */
    private Date endTime;

    /**
     * 节点耗时（毫秒）
     */
    private Long duration;

    /**
     * 分支标记（IF 真假/SWITCH 命中 case/循环轮次，可多段拼接）
     */
    private String branchInfo;

}
