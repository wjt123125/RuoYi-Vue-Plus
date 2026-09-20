package org.dromara.databus.component.script;

import lombok.Data;

/**
 * 脚本节点（script 普通脚本 / booleanScript 条件脚本）的节点参数。
 * <p>
 * 两物料共用此 cfg，由 {@code DatabusExecutor.registerScriptNodes} 在试运行建链前
 * 解析画布 properties.data 后用 {@code LiteFlowNodeBuilder} 注册脚本节点。
 * <pre>
 * {
 *   "language": "groovy",                       // 缺省 "groovy"，须为后端已装引擎清单内取值
 *   "script":   "def v = databusContext.read('$.request.code'); databusContext.save('$.script1.out', v)"
 * }
 * </pre>
 *
 * <p>脚本节点不是 Spring bean（无 {@code @LiteflowComponent}），nodeId 用画布数据空间名
 * （{@code properties.tag}，画布已强制唯一），保证 EL 引用与 {@code FlowBus} 注册一一对应。
 * 脚本通过 {@code databusContext}（{@link org.dromara.databus.context.DatabusContext} 的驼峰名）
 * 调 {@code read/save} 读写数据空间。
 *
 * @author databus
 */
@Data
public class ScriptCfg {

    /** 脚本语言，缺省 "groovy"；为空/blank 时由 DatabusExecutor 回退 "groovy" */
    private String language;

    /** 脚本文本，必填；为空表示该节点不是脚本（registerScriptNodes 会跳过） */
    private String script;
}
