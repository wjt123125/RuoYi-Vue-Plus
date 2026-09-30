package org.dromara.databus.executor;

import cn.hutool.core.lang.Tuple;
import com.yomahub.liteflow.slot.Slot;
import org.dromara.databus.context.DatabusContext;

/**
 * LiteFlow {@link Slot} 上取 {@link DatabusContext} 的统一入口。
 * <p>
 * 历史教训（设计档 §4.3 源码取证）：禁止用 {@code slot.getContextBean(Class)}——
 * 找不到上下文时该方法抛 {@code NoSuchContextBeanException} 而非返回 null，
 * 在 ruoyi-workflow 等非数据总线链路上很危险。统一遍历 {@code getContextBeanList()}
 * 自行 instanceof 匹配，缺失返回 null。
 *
 * @author databus
 */
public final class DatabusSlotSupport {

    private DatabusSlotSupport() {
    }

    /**
     * 从 Slot 上下文列表中安全查找 DatabusContext（非数据总线链路返回 null，不抛异常）。
     */
    public static DatabusContext findContext(Slot slot) {
        if (slot == null) {
            return null;
        }
        for (Tuple tuple : slot.getContextBeanList()) {
            Object bean = tuple.get(1);
            if (bean instanceof DatabusContext databusContext) {
                return databusContext;
            }
        }
        return null;
    }

}
