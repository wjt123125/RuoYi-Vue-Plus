package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

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
    @DatabusProp(
        label = "左值表达式", required = true, exprRole = ExprRole.DATA, order = 1,
        placeholder = "{{ $.路径 }}",
        description = "取要判断的值：{{ $.数据空间.字段 }}"
    )
    private String path;

    /**
     * 比较操作符：isTrue / isNull / notBlank / eq / ne / gt / ge / lt / le / contains。
     */
    @DatabusProp(
        label = "比较符", required = true, widget = WidgetKind.SELECT, order = 2,
        options = {
            @DatabusProp.Option(label = "为真 isTrue", value = "isTrue"),
            @DatabusProp.Option(label = "为空 isNull", value = "isNull"),
            @DatabusProp.Option(label = "非空白 notBlank", value = "notBlank"),
            @DatabusProp.Option(label = "等于 eq", value = "eq"),
            @DatabusProp.Option(label = "不等于 ne", value = "ne"),
            @DatabusProp.Option(label = "大于 gt", value = "gt"),
            @DatabusProp.Option(label = "大于等于 ge", value = "ge"),
            @DatabusProp.Option(label = "小于 lt", value = "lt"),
            @DatabusProp.Option(label = "小于等于 le", value = "le"),
            @DatabusProp.Option(label = "包含 contains", value = "contains")
        }
    )
    private String op;

    /**
     * 右操作数；字面量或 {@code {{ $.路径 }}} 表达式，执行前经 resolve 解析。
     * isNull / isTrue / notBlank 时可不填。
     */
    @DatabusProp(
        label = "右值", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 3,
        placeholder = "200 或 {{ $.路径 }}",
        description = "eq/ne/gt/ge/lt/le/contains 时填写；常量或 {{ $.路径 }} 表达式，数字/布尔请用 JSON 高级模式"
    )
    private Object value;
}
