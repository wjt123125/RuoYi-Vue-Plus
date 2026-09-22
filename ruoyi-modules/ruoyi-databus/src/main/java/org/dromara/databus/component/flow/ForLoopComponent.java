package org.dromara.databus.component.flow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeForComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.context.DatabusContext;

/**
 * 计数循环组件（注册名 {@code forLoop}）。
 * <p>
 * FOR 算子的条件位节点：EL 形态
 * {@code FOR(forLoop.tag("forLoop1").data("{...}")).DO(...)}，
 * {@link #processFor()} 返回循环次数，LiteFlow 从 0 开始逐轮执行 DO 表达式，
 * 体内节点通过 {@code this.getLoopIndex()} 或数据总线约定的
 * {@code $i}（可由 indexVar 自定义）拿到当前轮下标。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("forLoop")
public class ForLoopComponent extends NodeForComponent {

    @Override
    public int processFor() {
        ForLoopCfg cfg = this.getCmpData(ForLoopCfg.class);
        if (cfg == null || cfg.getCount() == null) {
            throw new ServiceException("计数循环缺少 count 配置（tag=" + this.getTag() + "）");
        }
        DatabusContext ctx = this.getContextBean(DatabusContext.class);
        // 先注册本层变量名：自定义名校验（超 3 层/重名）在此抛错，早于循环体执行
        LoopSupport.registerLoopVar(this, ctx, cfg.getIndexVar());

        int count = resolveCount(cfg.getCount(), ctx);
        if (count < 0) {
            throw new ServiceException("计数循环次数不能为负数: " + count);
        }
        ctx.reportStepSummary("共 " + count + " 次");
        return count;
    }

    /**
     * 解析循环次数：数字常量 / 数字字符串 / JSONPath 取值（数字）。
     */
    private int resolveCount(Object raw, DatabusContext ctx) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            String trimmed = str.trim();
            if (trimmed.matches("-?\\d+")) {
                return Integer.parseInt(trimmed);
            }
            // 路径（含外层 $i 占位）走上下文统一解析
            Object value = ctx.resolve(trimmed);
            if (value instanceof Number number) {
                return number.intValue();
            }
            throw new ServiceException("计数循环次数路径取到的值不是数字: " + trimmed + "，实际=" + value);
        }
        throw new ServiceException("计数循环 count 只支持整数或路径字符串，实际类型: "
            + raw.getClass().getSimpleName());
    }
}
