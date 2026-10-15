package org.dromara.databus.executor;

import com.yomahub.liteflow.lifecycle.PostProcessChainExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 链路嵌套深度探针（LiteFlow Chain 级生命周期钩子）。
 * <p>
 * <b>为什么需要</b>：{@code CmpStep} 只有 nodeId/tag/timeSpent/stepData 等字段，
 * <b>不含 chainId/depth/scope</b>，而 {@code slot.getExecuteStepQueue()} 是扁平队列——
 * 主链里嵌套子链（CHAIN 节点）时，子链的步骤会平铺进同一个队列，事后无法还原层级。
 * Chain 级钩子对<b>主链和每个子链各触发一次</b>且带 chainId，据此维护一条线程级链栈：
 * 进链 push、出链 pop，节点钩子（{@link NodeStepResultCollector}）打戳时读栈深即为该步的嵌套层级。
 * <p>
 * <b>两个用途</b>：
 * <ul>
 *     <li>观测：depth/ownerChain 顺 {@link StepResultPayload} 通道带到前端，执行明细按层级缩进；</li>
 *     <li>熔断：栈深超过 {@link #MAX_DEPTH} 直接抛异常终止本次执行，兜住绕过发布校验
 *     （循环引用 DFS）直改数据库造成的 A→B→A 成环——否则引擎会无限递归到栈溢出。</li>
 * </ul>
 * <p>
 * <b>已知边界</b>：WHEN 并行子链跑在 LiteFlow 的 worker 线程上，该线程没有经过主链的
 * before 钩子、栈为空，故并行分支内的步骤 depth 记 0。这与 LiteFlow 自家指标
 * （{@code liteflow.chain.executions} 的每线程样本栈）同口径，不做跨线程传递。
 * <p>
 * 钩子全局注册（ruoyi-workflow 等模块也用 LiteFlow），故 before/after 均先按
 * {@link DatabusSlotSupport#findContext(Slot)} 判定是否数据总线链路，非本模块的链不入栈、
 * 不参与熔断，两侧判定条件一致以保证 push/pop 严格配对。
 *
 * @author databus
 */
@Slf4j
@Component
public class ChainDepthTracker implements PostProcessChainExecuteLifeCycle {

    /**
     * 嵌套深度上限（含主链本身，即主链 + 9 层子链）。
     * 正常业务嵌套 2-3 层足够，留足余量又能及时兜住意外成环。
     */
    public static final int MAX_DEPTH = 10;

    /** 当前线程正在执行的链栈，栈底＝主链、栈顶＝最内层子链。 */
    private static final ThreadLocal<Deque<String>> CHAIN_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public void postProcessBeforeChainExecute(String chainId, Slot slot) {
        if (DatabusSlotSupport.findContext(slot) == null) {
            return;
        }
        Deque<String> stack = CHAIN_STACK.get();
        if (stack.size() >= MAX_DEPTH) {
            // 先清栈再抛：异常路径下 after 钩子可能不触发，避免线程池复用时残留错位
            String path = joinPath(stack);
            stack.clear();
            throw new IllegalStateException(
                "链路嵌套深度超过上限 " + MAX_DEPTH + " 层，疑似循环引用：" + path + " -> " + chainId);
        }
        stack.push(chainId);
    }

    @Override
    public void postProcessAfterChainExecute(String chainId, Slot slot) {
        if (DatabusSlotSupport.findContext(slot) == null) {
            return;
        }
        // 本钩子只做清理，绝不抛异常：它在 finally 中触发，抛错会覆盖业务原始异常
        try {
            Deque<String> stack = CHAIN_STACK.get();
            if (stack.isEmpty()) {
                return;
            }
            if (chainId != null && chainId.equals(stack.peek())) {
                stack.pop();
            } else {
                // 栈顶与出链不一致＝before/after 未严格配对（熔断抛出、框架异常路径），
                // 整栈作废，宁可丢一次层级信息也不让错位在后续请求里累积
                log.warn("[databus] 链路栈不配对，已清空 expect={} actualTop={}", chainId, stack.peek());
                stack.clear();
            }
            if (stack.isEmpty()) {
                CHAIN_STACK.remove();
            }
        } catch (Exception ex) {
            log.error("[databus] 链路深度探针出栈失败 chainId={}", chainId, ex);
        }
    }

    /**
     * 当前节点的嵌套深度：主链节点＝0，一级子链节点＝1，以此类推。
     * 栈空（WHEN 并行子链的 worker 线程）记 0。
     */
    public static int currentDepth() {
        return Math.max(0, CHAIN_STACK.get().size() - 1);
    }

    /**
     * 当前节点所属链的 chainId（栈空返回 null）。
     */
    public static String currentChainId() {
        return CHAIN_STACK.get().peek();
    }

    /**
     * 清空当前线程的链栈。执行收尾（含异常路径）必调，防工作线程复用串台。
     */
    public static void clear() {
        CHAIN_STACK.remove();
    }

    /** 栈底→栈顶拼成引用路径，仅用于熔断报错信息。 */
    private static String joinPath(Deque<String> stack) {
        List<String> path = new ArrayList<>(stack);
        // Deque.push 压栈顶，迭代顺序为顶→底，反转后得到 主链 -> ... -> 最内层
        java.util.Collections.reverse(path);
        return String.join(" -> ", path);
    }
}
