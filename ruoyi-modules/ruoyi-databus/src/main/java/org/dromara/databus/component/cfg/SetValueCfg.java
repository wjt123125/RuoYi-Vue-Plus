package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * 变量赋值组件（setValue）的节点参数。
 * <pre>
 * { "path": "$.setValue1.userName", "value": "{{ $.httpRequest1.response.data.name }}" }
 * </pre>
 *
 * @author databus
 */
@Data
public class SetValueCfg {

    /**
     * 写入目标 JSONPath（父路径不存在时自动创建）。
     */
    @DatabusProp(
        label = "写入路径",
        required = true,
        exprRole = ExprRole.TARGET,
        order = 1,
        placeholder = "$.setValue1.userName",
        description = "写入目标 JSONPath（父路径不存在自动创建），裸路径不求值"
    )
    private String path;

    /**
     * 写入值；字面量直接写入，动态值写 {@code {{ $.路径 }}} 由 resolve 求值（整字段保留原类型）。
     */
    @DatabusProp(
        label = "写入值",
        exprRole = ExprRole.DATA,
        widget = WidgetKind.JSON,
        order = 2,
        placeholder = "常量 或 {{ $.入参路径 }}",
        description = "字面量直接写入；动态值写 {{ $.路径 }}，整字段求值保留原类型。数字/布尔/对象字面量请用 JSON 高级模式"
    )
    private Object value;
}
