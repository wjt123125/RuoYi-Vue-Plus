package org.dromara.databus.component;

import com.jayway.jsonpath.TypeRef;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.context.DatabusContext;

/**
 * 数据总线组件基类。
 * <p>
 * 继承 LiteFlow {@link NodeComponent}，封装从 {@link DatabusContext} 取参 / 写参的通用方法，
 * 对应原系统 {@code BaseProcessor} 的 JSONPath get/save 能力，但去除 BPM 运行时依赖。
 * <p>
 * 子类只需实现 {@link #process()}，在其中通过 {@link #get(String)} / {@link #save(String, Object)}
 * 读写上下文，无需关心 LiteFlow slot 与上下文获取细节。
 *
 * @author databus
 */
@Slf4j
public abstract class DatabusNodeComponent extends NodeComponent {

    /**
     * 获取数据总线上下文（从 LiteFlow slot 中取出当前执行绑定的 DatabusContext）。
     */
    protected DatabusContext getDatabusContext() {
        return getContextBean(DatabusContext.class);
    }

    /**
     * 从上下文读取指定路径的值（路径必须存在）。
     *
     * @param path JSONPath，如 {@code $.request.userId}
     */
    protected <T> T get(String path) {
        return getDatabusContext().read(path);
    }

    /**
     * 从上下文读取指定路径的值并按 {@link TypeRef} 转换。
     */
    protected <T> T get(String path, TypeRef<T> typeRef) {
        return getDatabusContext().read(path, typeRef);
    }

    /**
     * 从上下文读取指定路径的值（路径不存在时返回 {@code null}）。
     */
    protected <T> T getOptional(String path) {
        return getDatabusContext().readOptional(path);
    }

    /**
     * 从上下文读取指定路径的值（路径不存在时返回默认值）。
     */
    protected <T> T getOrDefault(String path, T defaultValue) {
        return getDatabusContext().readOptional(path, defaultValue);
    }

    /**
     * 向上下文写入指定路径的值（父路径不存在时自动创建）。
     *
     * @param path  JSONPath，如 {@code $.response.data}
     * @param value 要写入的值
     */
    protected void save(String path, Object value) {
        getDatabusContext().write(path, value);
    }

    /**
     * 解析参数：纯路径读取、混合路径替换、动态变量原样保留。
     *
     * @param input 参数原始值（可能是路径、模板或字面量）
     * @return 解析后的值
     */
    protected Object resolveParam(Object input) {
        return getDatabusContext().resolve(input);
    }
}
