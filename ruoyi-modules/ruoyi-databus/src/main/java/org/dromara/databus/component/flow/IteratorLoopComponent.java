package org.dromara.databus.component.flow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeIteratorComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.context.DatabusContext;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * 迭代循环组件（注册名 {@code iteratorLoop}）。
 * <p>
 * ITERATOR 算子的条件位节点：EL 形态
 * {@code ITERATOR(iteratorLoop.tag("iteratorLoop1").data("{...}")).DO(...)}。
 * {@link #processIterator()} 返回数据源的迭代器，LiteFlow 逐轮把当前对象挂到
 * DO 内节点（体内可用 {@code this.getCurrLoopObj()}），当前轮下标则按数据总线约定
 * 以 {@code $i}（可由 indexVar 自定义）注入路径解析。
 * <p>
 * 数据源支持 {@link Collection} / {@link Iterable} / Java 数组；
 * 为 null 按空集合处理（0 轮）；其他类型直接报配置错误。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("iteratorLoop")
public class IteratorLoopComponent extends NodeIteratorComponent {

    @Override
    public Iterator<?> processIterator() {
        IteratorLoopCfg cfg = this.getCmpData(IteratorLoopCfg.class);
        if (cfg == null || cfg.getSource() == null || cfg.getSource().isBlank()) {
            throw new ServiceException("迭代循环缺少 source 配置（数组/集合路径，tag=" + this.getTag() + "）");
        }
        DatabusContext ctx = this.getContextBean(DatabusContext.class);
        // 先注册本层变量名：超深/重名早报错
        LoopSupport.registerLoopVar(this, ctx, cfg.getIndexVar());

        Object source = ctx.readOptional(cfg.getSource().trim());
        if (source == null) {
            ctx.reportStepSummary("数据源为空，0 项");
            return Collections.emptyIterator();
        }
        Iterator<?> iterator = toIterator(source, cfg.getSource());
        int size = source instanceof Collection<?> collection ? collection.size() : -1;
        ctx.reportStepSummary(size >= 0 ? "共 " + size + " 项" : "开始迭代");
        return iterator;
    }

    /**
     * 把数据源统一转成迭代器；非集合/数组/可迭代对象时报明确的配置错误。
     */
    private Iterator<?> toIterator(Object source, String path) {
        if (source instanceof Collection<?> collection) {
            return collection.iterator();
        }
        if (source instanceof Iterable<?> iterable) {
            return iterable.iterator();
        }
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            return new Iterator<>() {
                private int cursor = 0;

                @Override
                public boolean hasNext() {
                    return cursor < length;
                }

                @Override
                public Object next() {
                    return Array.get(source, cursor++);
                }
            };
        }
        throw new ServiceException("迭代循环数据源不是数组或集合: " + path
            + "，实际类型=" + source.getClass().getSimpleName());
    }
}
