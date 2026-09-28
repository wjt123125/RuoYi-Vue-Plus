package org.dromara.databus.component.data;

import lombok.Data;

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
    private String path;

    /**
     * 写入值；字面量直接写入，动态值写 {@code {{ $.路径 }}} 由 resolve 求值（整字段保留原类型）。
     */
    private Object value;
}
