package org.dromara.databus.el.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 附加属性（挂在表达式或节点上的 id / tag / data 三件套）。
 * <p>
 * 对应 EL 语法中的链式调用，例如：
 * <pre>THEN(a, b).id("myId").tag("myTag").data("{\"k\":1}")</pre>
 * 三个字段全部允许为 null（生成 EL 时为 null 的字段直接跳过不拼接）。
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
}
