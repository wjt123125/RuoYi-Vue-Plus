package org.dromara.databus.component.schema.enums;

/**
 * 物料对应的 LiteFlow 节点类型，对齐前端 cmp-defs.ts lfNodeType。
 *
 * @author databus
 */
public enum NodeTypeKind {

    /** 普通件（NodeComponent）。 */
    NODE,

    /** 布尔条件件（NodeBooleanComponent，可入 IF/WHILE 条件槽）。 */
    BOOLEAN,

    /** for 循环件（NodeForComponent）。 */
    FOR,

    /** 迭代循环件（NodeIteratorComponent）。 */
    ITERATOR,

    /** 选择路由件（NodeSwitchComponent）。 */
    SWITCH
}
