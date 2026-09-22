package org.dromara.databus.component.flow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeSwitchComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.context.DatabusContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 选择路由组件（注册名 {@code switchRoute}）。
 * <p>
 * SWITCH 算子的条件位节点：EL 形态
 * {@code SWITCH(switchRoute.tag("switchRoute1").data("{...}")).to(THEN(...).tag("caseA"), ...)}。
 * {@link #processSwitch()} 按 cfg 中 cases 的顺序把 source 的实际值与各 value 比较，
 * 命中第一条即返回 {@code ":分支名"}——冒号前缀走 LiteFlow 的 tag 匹配模式
 * （SwitchCondition 源码按 targetList 中表达式的 tag 比对），使分支可以是任意
 * THEN/WHEN 表达式且各分支节点仍保留自己的数据空间 tag。
 * <p>
 * 本档不提供 DEFAULT 兜底：全部未命中抛带当前值与候选清单的业务异常，
 * DEFAULT 分支与 BREAK 一并列入后续迭代（见规格档 §6）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("switchRoute")
public class SwitchRouteComponent extends NodeSwitchComponent {

    @Override
    public String processSwitch() {
        SwitchRouteCfg cfg = this.getCmpData(SwitchRouteCfg.class);
        if (cfg == null || cfg.getSource() == null || cfg.getSource().isBlank()) {
            throw new ServiceException("选择路由缺少 source 配置（判断值路径，tag=" + this.getTag() + "）");
        }
        List<SwitchRouteCfg.CaseRoute> cases = cfg.getCases();
        if (cases == null || cases.isEmpty()) {
            throw new ServiceException("选择路由缺少 cases 配置（tag=" + this.getTag() + "）");
        }

        DatabusContext ctx = this.getContextBean(DatabusContext.class);
        Object actual = ctx.readOptional(cfg.getSource().trim());

        for (SwitchRouteCfg.CaseRoute route : cases) {
            if (route == null || route.getTarget() == null || route.getTarget().isBlank()) {
                throw new ServiceException("选择路由存在 target 为空的分支配置，请补全 case 分支名");
            }
            Object expected = ctx.resolve(route.getValue());
            if (equalsValue(actual, expected)) {
                String target = route.getTarget().trim();
                ctx.reportStepSummary("命中分支 " + target);
                return ":" + target;
            }
        }

        List<String> candidateValues = cases.stream()
            .map(c -> String.valueOf(c.getValue()))
            .toList();
        throw new ServiceException("SWITCH 未匹配到任何分支：当前值=" + actual
            + "，候选值=" + candidateValues);
    }

    /**
     * 相等判断：两个数字按数值比较（避免 Integer 200 与 Long 200 不相等），其余走 Objects.equals。
     * 与 {@code ConditionComponent} 的比较口径保持一致。
     */
    private boolean equalsValue(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(actual, expected);
    }
}
