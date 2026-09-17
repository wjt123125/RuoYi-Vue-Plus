package org.dromara.databus.component.logical;

import lombok.Data;

/**
 * 条件判断组件（condition）的节点参数。
 * <p>
 * 画布以裸 JSON 配置，对应 EL 中节点的 {@code .data("...")}：
 * <pre>
 * { "path": "$.httpRequest1.response.code", "op": "eq", "value": 200 }
 * </pre>
 *
 * @author databus
 */
@Data
public class ConditionCfg {

    /**
     * 左操作数的 JSONPath（从上下文文档读取）。
     */
    private String path;

    /**
     * 比较操作符：isTrue / isNull / notBlank / eq / ne / gt / ge / lt / le / contains。
     */
    private String op;

    /**
     * 右操作数；可以是字面量，也可以是裸路径（{@code $.xxx}），执行前经上下文解析。
     * isNull / isTrue / notBlank 时可不填。
     */
    private Object value;
}
