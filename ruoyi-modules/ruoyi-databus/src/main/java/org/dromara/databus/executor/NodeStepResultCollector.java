package org.dromara.databus.executor;

import cn.hutool.core.lang.Tuple;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.flow.entity.CmpStep;
import com.yomahub.liteflow.lifecycle.PostProcessNodeExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.context.DatabusContext;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Iterator;

/**
 * 节点执行结果采集器（LiteFlow 2.16.1+ 全局生命周期钩子）。
 * <p>
 * 每个组件执行结束后（成功/失败都回调，after 在 finally 中触发），采集两类观测信息挂到本步
 * {@link CmpStep} 上（{@link StepResultPayload}）：
 * <ul>
 *     <li><b>人话摘要</b>：组件在 process 收尾时通过 DatabusContext 自报的一句话；</li>
 *     <li><b>数据明细</b>：该步数据空间 {@code $.<tag>} 子树的<b>当场</b> JSON 快照，
 *     循环同一组件多轮执行时各保各的现场。</li>
 * </ul>
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
        // 不需要前置处理
    }

    @Override
    public void postProcessAfterNodeExecute(NodeComponent cmp, long timeSpent, Exception e) {
        try {
            Slot slot = cmp.getSlot();
            DatabusContext context = resolveDatabusContext(slot);
            if (context == null) {
                return;
            }

            String summary = context.consumeStepSummary();
            String detailJson;
            if (cmp instanceof NodeBooleanComponent booleanCmp) {
                // 条件组件不写数据空间，它的"产出"是布尔判定（循环中 getMetaValueKey 天然带轮次）
                Boolean result = booleanCmp.getItemResultMetaValue(cmp.getSlotIndex());
                detailJson = "{\"conditionResult\":" + result + "}";
                if (summary == null) {
                    summary = "条件判定为 " + result;
                }
            } else {
                detailJson = context.snapshotDataSpace(cmp.getTag());
            }

            CmpStep currentStep = findCurrentStep(slot, cmp);
            if (currentStep != null) {
                currentStep.setStepData(new StepResultPayload(summary, detailJson));
            }
        } catch (Exception ex) {
            // 观测逻辑任何异常都只记录，绝不影响业务执行
            log.error("[databus] 节点步骤结果采集失败 nodeId={}", cmp.getNodeId(), ex);
        }
    }

    /**
     * 从 slot 上下文列表中安全查找 DatabusContext（缺失返回 null，不抛异常）。
     */
    private DatabusContext resolveDatabusContext(Slot slot) {
        for (Tuple tuple : slot.getContextBeanList()) {
            Object bean = tuple.get(1);
            if (bean instanceof DatabusContext databusContext) {
                return databusContext;
            }
        }
        return null;
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
