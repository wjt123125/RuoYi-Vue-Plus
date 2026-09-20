package org.dromara.databus.el.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附加属性（挂在表达式或节点上的 id / tag / data，以及仅前端编辑态使用的 title）。
 * <p>
 * 对应 EL 语法中的链式调用，例如：
 * <pre>THEN(a, b).id("myId").tag("myTag").data("{\"k\":1}")</pre>
 * id / tag / data 三个字段全部允许为 null（生成 EL 时为 null 的字段直接跳过不拼接）。
 * <p>
 * {@code title} 是数据总线扩展的「节点标题」（用户可覆盖的业务名，缺省由前端按组件
 * 类型 + cfg 推断），属于纯编辑态字段：不参与 EL 生成，仅随画布 JSON 往返保留，
 * 试运行时透传到 {@code NodeStep.title} 供结果面板展示。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Properties {

    /**
     * 表达式/节点的 id，对应 EL 的 {@code .id("xxx")}。
     * 注意：LiteFlow 会给 Condition 生成默认 id（如 condition-then），
     * 解析时这种默认 id 会被过滤掉（见 AbstractExpressParser#getPropertyId）。
     */
    private String id;

    /**
     * 标签，对应 EL 的 {@code .tag("xxx")}，常用于同一个组件的多场景区分。
     */
    private String tag;

    /**
     * 组件参数（LiteFlow 的 cmpData），对应 EL 的 {@code .data("xxx")}。
     * 通常是一个 JSON 字符串，运行时传给组件的 process 方法。
     */
    private String data;

    /**
     * 节点标题（数据总线扩展，纯编辑态字段）。
     * <p>
     * 用户为该节点填写的业务名（如「新建 用户表」），留空为 null，由前端按组件类型
     * 与 cfg 实时推断默认标题。不参与 EL 生成，仅随画布 JSON 往返，试运行时透传到
     * 执行结果的 {@code NodeStep.title}。
     */
    private String title;
}
