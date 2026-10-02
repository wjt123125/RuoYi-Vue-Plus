package org.dromara.databus.component.schema.enums;

/**
 * 配置字段的表达式角色（与 project_memory 字段角色铁律同源）。
 *
 * @author databus
 */
public enum ExprRole {

    /** 普通字面量（缺省），不做表达式求值。 */
    LITERAL,

    /** 要数据：值为常量或 {@code {{ $.路径 }}} 表达式，渲染 ExprInput/ExprValue。 */
    DATA,

    /** 起名字：裸 JSONPath（不求值），渲染 PathInput。 */
    TARGET
}
