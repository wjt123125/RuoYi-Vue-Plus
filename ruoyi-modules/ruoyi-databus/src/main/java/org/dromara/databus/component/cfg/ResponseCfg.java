package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * 响应组装组件（response）的节点参数。
 * <pre>
 * { "result": true, "msg": "{{ $.httpRequest1.response.msg }}", "dataPath": "{{ $.fieldMap1 }}" }
 * </pre>
 *
 * @author databus
 */
@Data
public class ResponseCfg {

    /**
     * 执行结果：布尔字面量，或解析后为布尔/布尔字符串的路径。
     */
    @DatabusProp(
        label = "执行结果", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 1,
        placeholder = "true 或 {{ $.路径 }}",
        description = "链路成功/失败：布尔字面量，或求值结果为布尔/布尔字符串的 {{ $.路径 }}；不填默认 true"
    )
    private Object result;

    /**
     * 提示信息：字面量，或 {@code {{ $.路径 }}} 表达式。
     */
    @DatabusProp(
        label = "提示信息", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 2,
        placeholder = "字面量 或 {{ $.路径 }}",
        description = "返回给调用方的 msg，可嵌 {{ $.路径 }} 表达式"
    )
    private Object msg;

    /**
     * 返回数据所在的表达式（{@code {{ $.路径 }}}，整字段求值保留原类型）；不填则 data 为 null。
     */
    @DatabusProp(
        label = "返回数据路径", exprRole = ExprRole.DATA, order = 3,
        placeholder = "{{ $.数据空间.数据对象 }}",
        description = "整字段求值后作为响应 data；不填则 data 为 null"
    )
    private String dataPath;
}
