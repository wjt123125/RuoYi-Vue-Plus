package org.dromara.databus.component.flow;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.core.NodeForComponent;
import com.yomahub.liteflow.core.NodeIteratorComponent;
import org.dromara.databus.context.DatabusContext;

/**
 * 循环索引占位符与 LiteFlow 循环下标的对接工具（规格档 databus-loop-component.md §3）。
 * <p>
 * 组件不感知循环进出：全局节点生命周期钩子在每个节点执行前调
 * {@link #syncBeforeNode}，由 LiteFlow 的 {@link NodeComponent#getLoopIndex()} /
 * {@link #getPreNLoopIndex} 探测当前节点所处的真实循环深度，驱动
 * {@link DatabusContext#reconcileLoopIndices} 进层压变量名、出层截断。
 * <p>
 * 关键机制（LiteFlow 2.16.1.3 源码实证）：
 * <ul>
 *     <li>{@code getLoopIndex()} 在循环体外返回 null（不抛异常），故循环外节点
 *     探测到深度 0；{@code getPreNLoopIndex(n)} 返回外 n 层下标，栈深不足时返回 null；</li>
 *     <li>{@code LoopCondition.setLoopIndex} 经 {@code LiteflowMetaOperator.getNodes}
 *     （递归收集 DO 表达式内全部节点）把外层下标压到内层循环控制节点自己的栈上，
 *     故嵌套循环的控制组件在 processFor/processIterator 时探测到的是<b>外层</b>深度；</li>
 *     <li>0 轮循环的控制组件已注册待消费变量名但循环体从未执行——下个循环控制节点
 *     执行前统一清残留，避免它被后续无关循环错配。</li>
 * </ul>
 *
 * @author databus
 */
public final class LoopSupport {

    /** 防御性上限：真实嵌套深度超过该值视为探测异常（业务上限由 DatabusContext 校验为 3 层）。 */
    private static final int PROBE_HARD_LIMIT = 32;

    private LoopSupport() {
    }

    /**
     * 探测组件当前所处的循环深度（0 = 不在任何循环体内）。
     */
    public static int probeDepth(NodeComponent cmp) {
        if (cmp.getLoopIndex() == null) {
            return 0;
        }
        int depth = 1;
        while (depth < PROBE_HARD_LIMIT && cmp.getPreNLoopIndex(depth) != null) {
            depth++;
        }
        return depth;
    }

    /**
     * 循环控制组件（forLoop/iteratorLoop）在 processFor/processIterator 内注册本层变量名。
     * <p>
     * 此刻循环体尚未开始，本组件探测到的是外层深度，本层 = 外层 + 1。
     *
     * @param indexVar 自定义变量名（不含 {@code $}），空白走按层默认 i/j/k
     */
    public static void registerLoopVar(NodeComponent loopCmp, DatabusContext ctx, String indexVar) {
        int nextDepth = probeDepth(loopCmp) + 1;
        ctx.pushPendingLoopVar(indexVar, nextDepth);
    }

    /**
     * 节点执行前对账：清 0 轮循环残留的待消费名（仅循环控制节点），
     * 再按探测到的真实深度重建本线程的「变量名 → 当前轮下标」表。
     * <p>
     * 各层下标取值：最内层 = {@code getLoopIndex()}；第 d 层（d &lt; depth）
     * = {@code getPreNLoopIndex(depth - d)}（Node 栈按下标条件 hashCode 分层，嵌套天然隔离）。
     */
    public static void syncBeforeNode(NodeComponent cmp, DatabusContext ctx) {
        if (cmp instanceof NodeForComponent || cmp instanceof NodeIteratorComponent) {
            ctx.clearPendingLoopVars();
        }
        int depth = probeDepth(cmp);
        ctx.reconcileLoopIndices(depth, d -> {
            if (d == depth) {
                return cmp.getLoopIndex();
            }
            return cmp.getPreNLoopIndex(depth - d);
        });
    }
}
