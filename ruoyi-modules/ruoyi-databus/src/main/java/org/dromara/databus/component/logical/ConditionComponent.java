package org.dromara.databus.component.logical;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeBooleanComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.context.DatabusContext;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Objects;

/**
 * 条件判断组件（注册名 {@code condition}）。
 * <p>
 * 布尔组件，作为 IF / WHILE 的条件位使用。按节点参数 {@link ConditionCfg} 中的
 * path/op/value 对上下文数据做比较，{@link #processBoolean()} 返回判断结果。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("condition")
public class ConditionComponent extends NodeBooleanComponent {

    @Override
    public boolean processBoolean() {
        ConditionCfg cfg = this.getCmpData(ConditionCfg.class);
        if (cfg == null || cfg.getOp() == null) {
            throw new ServiceException("条件组件缺少 op 配置（tag=" + this.getTag() + "）");
        }
        DatabusContext ctx = this.getContextBean(DatabusContext.class);
        Object actual = cfg.getPath() == null ? null : ctx.readOptional(cfg.getPath());
        Object expected = ctx.resolve(cfg.getValue());

        String op = cfg.getOp().trim();
        return switch (op) {
            case "isTrue" -> toBoolean(actual);
            case "isNull" -> actual == null;
            case "notBlank" -> actual != null && !actual.toString().isBlank();
            case "eq" -> equalsValue(actual, expected);
            case "ne" -> !equalsValue(actual, expected);
            case "gt" -> compareNumber(actual, expected) > 0;
            case "ge" -> compareNumber(actual, expected) >= 0;
            case "lt" -> compareNumber(actual, expected) < 0;
            case "le" -> compareNumber(actual, expected) <= 0;
            case "contains" -> containsValue(actual, expected);
            default -> throw new ServiceException("不支持的条件操作符: " + cfg.getOp());
        };
    }

    /**
     * 布尔宽容转换：真正的 Boolean 直接取；其他类型按字符串 "true" 判定。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    /**
     * 相等判断：两个数字按数值比较（避免 Integer 200 与 Long 200 不相等），其余走 Objects.equals。
     */
    private boolean equalsValue(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return Objects.equals(actual, expected);
    }

    /**
     * 数值大小比较；任一侧不是数字时抛异常（节点失败，便于在试运行中暴露配置错误）。
     */
    private int compareNumber(Object actual, Object expected) {
        if (!(actual instanceof Number) || !(expected instanceof Number)) {
            throw new ServiceException("大小比较要求两侧都是数字，实际值=" + actual + "，比较值=" + expected);
        }
        return new BigDecimal(actual.toString()).compareTo(new BigDecimal(expected.toString()));
    }

    /**
     * 包含判断：左值为集合时判断元素归属，否则按字符串包含处理。
     */
    private boolean containsValue(Object actual, Object expected) {
        if (actual instanceof Collection<?> collection) {
            return collection.contains(expected);
        }
        return actual != null && expected != null && actual.toString().contains(expected.toString());
    }
}
