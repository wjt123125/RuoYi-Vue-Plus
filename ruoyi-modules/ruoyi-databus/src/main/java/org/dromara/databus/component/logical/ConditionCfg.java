package org.dromara.databus.component.logical;

import lombok.Data;

/**
 * 条件判断组件（condition）的节点参数。
 * <p>
 * 画布以裸 JSON 配置，对应 EL 中节点的 {@code .data("...")}：
 * <pre>
 * { "path": "{{ $.httpRequest1.response.code }}", "op": "eq", "value": 200 }
 * </pre>
 *
 * @author databus
 */
@Data
public class ConditionCfg {

    /**
     * 左操作数表达式（要数据，{@code {{ $.路径 }}}，resolve 求值）。
     */
    private String path;

    /**
     * 比较操作符：isTrue / isNull / notBlank / eq / ne / gt / ge / lt / le / contains。
     */
    private String op;

    /**
     * 右操作数；字面量或 {@code {{ $.路径 }}} 表达式，执行前经 resolve 解析。
     * isNull / isTrue / notBlank 时可不填。
     */
    private Object value;
}
