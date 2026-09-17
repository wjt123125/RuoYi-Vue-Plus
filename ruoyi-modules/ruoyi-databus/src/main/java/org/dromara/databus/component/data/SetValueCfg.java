package org.dromara.databus.component.data;

import lombok.Data;

/**
 * 变量赋值组件（setValue）的节点参数。
 * <pre>
 * { "path": "$.setValue1.userName", "value": "$.httpRequest1.response.data.name" }
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
     * 写入值；字面量直接写入，裸路径（{@code $.xxx}）从上下文取原值，混合字符串做片段替换。
     */
    private Object value;
}
