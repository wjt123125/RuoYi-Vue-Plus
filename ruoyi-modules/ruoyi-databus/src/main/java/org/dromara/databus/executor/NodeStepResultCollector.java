package org.dromara.databus.executor;

import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.core.NodeSwitchComponent;
import com.yomahub.liteflow.flow.entity.CmpStep;
import com.yomahub.liteflow.lifecycle.PostProcessNodeExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.databus.component.flow.LoopSupport;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.enums.ExecutionStatusEnum;
import org.dromara.databus.executor.trace.ExecutionTrace;
import org.dromara.databus.executor.trace.NodeTraceRow;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * 节点执行结果采集器（LiteFlow 2.16.1+ 全局生命周期钩子）。
 * <p>
 * 每个组件执行结束后（成功/失败都回调，after 在 finally 中触发），采集两类观测信息挂到本步
 * {@link CmpStep} 上（{@link StepResultPayload}），供<b>试运行结果弹窗</b>消费：
 * <ul>
 *     <li><b>人话摘要</b>：组件在 process 收尾时通过 DatabusContext 自报的一句话；</li>
 *     <li><b>数据明细</b>：该步数据空间 {@code $.<tag>} 子树的<b>当场</b> JSON 快照，
 *     循环同一组件多轮执行时各保各的现场。</li>
 * </ul>
 * <p>
 * 2026-09-29 合并演化（设计档 §4.3）：同一 Bean 内新增<b>执行记录</b>分支——上下文挂了
 * {@link ExecutionTrace} 且为 FULL 档时，把节点行（类型/tag/状态/耗时/异常/分支/IO 快照）
 * 追加进追踪牌的线程安全缓冲，afterFlow 钩子一次性批量落库。不另注册第二个生命周期 Bean
 * （同接口多实现的回调顺序框架无保证）。BASIC 档与试运行（无牌）零额外开销。
 * <p>
 * <b>为什么不直接在钩子里调 {@code cmp.setStepData()}</b>：2.16.1.3 源码中
 * {@code NodeComponent.execute()} 的 finally 先执行 {@code cmpStep.setStepData(refNode.getStepData())}
 * （约 :170），之后才回调本钩子（约 :187），写 refNode 已赶不上本次拷贝。
 * 这里改为从 {@code slot.getExecuteSteps()} 实时队列里反查出当前 CmpStep 直接挂载。
 * <p>
 * 该钩子是全局注册的（ruoyi-workflow 等模块也用 LiteFlow），上下文中没有
 * {@link DatabusContext} 时直接跳过，绝不影响其他链路。
 *
 * @author databus
 */
@Slf4j
@Component
public class NodeStepResultCollector implements PostProcessNodeExecuteLifeCycle {

    @Override
    public void postProcessBeforeNodeExecute(NodeComponent cmp) {
        try {
            DatabusContext context = DatabusSlotSupport.findContext(cmp.getSlot());
            if (context == null) {
                return;
            }
            // 按当前节点探测到的循环深度对账 $i/$j/$k 索引表（进层/出层/逐轮刷新）
            LoopSupport.syncBeforeNode(cmp, context);
            // FULL 档执行记录：拍节点执行前数据树整树快照（ThreadLocal 线程隔离，after 消费）
            ExecutionTrace trace = context.getExecutionTrace();
            if (trace != null && trace.isFull()) {
                trace.captureInput(context.toJsonString());
            }
        } catch (Exception ex) {
            // 观测/索引逻辑任何异常都只记录，绝不影响业务执行
            log.error("[databus] 循环索引同步失败 nodeId={}", cmp.getNodeId(), ex);
        }
    }

    @Override
    public void postProcessAfterNodeExecute(NodeComponent cmp, long timeSpent, Exception e) {
        try {
            Slot slot = cmp.getSlot();
            DatabusContext context = DatabusSlotSupport.findContext(slot);
            if (context == null) {
                return;
            }

            String summary = context.consumeStepSummary();
            String detailJson;
            // 布尔判定结果（IF/WHILE/AND/OR 等条件节点）：同时用于明细快照与分支标记
            Boolean booleanResult = null;
            if (cmp instanceof NodeBooleanComponent booleanCmp) {
                // 条件组件不写数据空间，它的"产出"是布尔判定（循环中 getMetaValueKey 天然带轮次）
                booleanResult = booleanCmp.getItemResultMetaValue(cmp.getSlotIndex());
                detailJson = "{\"conditionResult\":" + booleanResult + "}";
                if (summary == null) {
                    summary = "条件判定为 " + booleanResult;
                }
            } else {
                detailJson = context.snapshotDataSpace(cmp.getTag());
            }

            CmpStep currentStep = findCurrentStep(slot, cmp);
            if (currentStep != null) {
                currentStep.setStepData(new StepResultPayload(summary, detailJson));
            }

            // FULL 档执行记录：追加节点轨迹行（WHEN 并发安全队列），试运行/ BASIC 档无牌不进
            ExecutionTrace trace = context.getExecutionTrace();
            if (trace != null && trace.isFull()) {
                trace.appendNode(toTraceRow(cmp, e, currentStep, detailJson,
                    trace.consumeInput(), booleanResult));
            }
        } catch (Exception ex) {
            // 观测逻辑任何异常都只记录，绝不影响业务执行
            log.error("[databus] 节点步骤结果采集失败 nodeId={}", cmp.getNodeId(), ex);
        }
    }

    /**
     * 组装节点轨迹行（纯数据，不持有 cmp/slot/step 引用）。
     */
    private NodeTraceRow toTraceRow(NodeComponent cmp, Exception e, CmpStep currentStep,
                                    String outputJson, String inputJson, Boolean booleanResult) {
        NodeTraceRow row = new NodeTraceRow();
        // 实例 id 必须取 refNode：2.16.1.3 中 CmpStep.getNodeInstanceId() 是死 getter
        // （setter 全源码零调用），真实 id 由框架在开 enable-node-instance-id 时挂到 Node 上；
        // 未开开关或动态建链时为 null（列可空，循环多轮另靠 branch_info 的 LOOP 轮次区分）
        row.setNodeInstanceId(currentStep != null && currentStep.getRefNode() != null
            ? currentStep.getRefNode().getNodeInstanceId()
            : null);
        // tag 理论非空（数据总线 EL 每个叶子都打 tag）；双重兜底防非空列插库失败
        row.setTag(StringUtils.defaultIfBlank(cmp.getTag(), cmp.getNodeId()));
        row.setNodeType(StringUtils.defaultIfBlank(cmp.getNodeId(), "UNKNOWN"));
        row.setInputJson(inputJson);
        row.setOutputJson(outputJson);
        row.setStatus(e == null
            ? ExecutionStatusEnum.SUCCESS.getCode()
            : ExecutionStatusEnum.FAILED.getCode());
        row.setErrorMsg(e != null ? e.getMessage() : null);
        if (currentStep != null) {
            row.setStartTime(currentStep.getStartTime());
            row.setEndTime(currentStep.getEndTime());
            row.setDuration(currentStep.getTimeSpent());
            row.setBranchInfo(resolveBranchInfo(cmp, currentStep, booleanResult));
        }
        return row;
    }

    /**
     * 解析分支/循环标记，多段用 {@code "; "} 拼接：
     * <ul>
     *     <li>布尔组件（IF/WHILE/AND/OR 条件）：{@code IF=true/false}，直接复用本钩子
     *     已通过 {@code getItemResultMetaValue} 取到的判定值；</li>
     *     <li>SWITCH 路由：{@code SWITCH=<命中节点>}，取 NodeSwitchComponent 的公开取值通道。</li>
     * </ul>
     * 注意：Slot 的 getIfResult/getSwitchResult 键是 {@code getMetaValueKey()}
     * （组件实现类名，非 tag），外部包拿不到 protected 的键，必须走组件公开方法
     * （2026-09-29 源码核实，按 tag 直取恒为 null）。
     * <ul>
     *     <li>循环体内节点再追加 {@code LOOP=<轮次>}，取 CmpStep.loopIndex（0 基）。</li>
     * </ul>
     * 取不到任何标记时返回 null（普通顺序节点）。
     */
    private String resolveBranchInfo(NodeComponent cmp, CmpStep step, Boolean booleanResult) {
        List<String> parts = new ArrayList<>(2);
        if (booleanResult != null) {
            parts.add("IF=" + booleanResult);
        } else if (cmp instanceof NodeSwitchComponent switchCmp) {
            String target = switchCmp.getItemResultMetaValue(cmp.getSlotIndex());
            if (StringUtils.isNotBlank(target)) {
                parts.add("SWITCH=" + target);
            }
        }
        if (step.getLoopIndex() != null) {
            parts.add("LOOP=" + step.getLoopIndex());
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * 反查当前正在执行（钩子此刻所属）的 CmpStep：
     * 步骤在节点执行前已入 slot 队列（{@code NodeComponent.execute} 约 :109），
     * 从队尾倒序找「同一组件实例 + 同一执行线程 + 尚未挂过本载荷」的第一步。
     * <ul>
     *     <li>循环多轮：同实例旧步骤已挂载荷被跳过，自然定位到本轮；</li>
     *     <li>WHEN 并行：不同工作线程用 threadName 区分；</li>
     *     <li>同线程串行 THEN(a,a)：前一步已挂载荷，定位到后一步。</li>
     * </ul>
     */
    private CmpStep findCurrentStep(Slot slot, NodeComponent cmp) {
        String threadName = Thread.currentThread().getName();
        Deque<CmpStep> steps = slot.getExecuteSteps();
        Iterator<CmpStep> descending = steps.descendingIterator();
        while (descending.hasNext()) {
            CmpStep step = descending.next();
            if (step.getInstance() == cmp
                && threadName.equals(step.getThreadName())
                && !(step.getStepData() instanceof StepResultPayload)) {
                return step;
            }
        }
        return null;
    }
}
