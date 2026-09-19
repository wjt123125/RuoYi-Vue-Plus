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

    /**
     * 自报一句「人话执行结果」，显示在试运行结果步骤表的「执行结果」列。
     * <p>风格：动宾 + 数量 + 关键标识（必要时附带对 tag 外数据的影响，如「ID 已回写来源 N 条」），
     * 一句长中文 30 字以内；在 {@link #process()} 收尾处调用，不调用时前端兜底显示「完成」。
     * <p><b>只写同一行表格上没有的增量信息</b>——步骤表已有数据空间 tag、组件名、成败、耗时四列，
     * 摘要中禁止出现：成败词（成功/失败/完成/已创建/已启动等）、耗时、tag 与组件名，
     * 以及无行动意义的流水信息（如「响应 N 字节」）。区分业务分支状态的词不算违规
     * （如「此前已结束」「成功 N 个任务」）。准则与反例见
     * {@code docs/wiki/databus-preview-step-result.md} §3。
     *
     * @param text 结果摘要，如 {@code "新建 BO 2 个：BO-001、BO-002"}
     */
    protected void resultSummary(String text) {
        getDatabusContext().reportStepSummary(text);
    }
}
