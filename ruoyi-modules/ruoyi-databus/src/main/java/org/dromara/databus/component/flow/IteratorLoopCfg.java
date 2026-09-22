package org.dromara.databus.component.flow;

import lombok.Data;

/**
 * 迭代循环组件（iteratorLoop）的节点参数。
 * <pre>
 * { "source": "$.request.items" }
 * { "source": "$.groups[$i].users", "indexVar": "u" }
 * </pre>
 *
 * @author databus
 */
@Data
public class IteratorLoopCfg {

    /**
     * 被迭代数据的 JSONPath：值必须是数组或集合（{@code List}/{@code Collection}/
     * Java 数组/{@code Iterable}）；值为 null 时按空集合处理（循环体零次执行）。
     * 路径中可用外层循环索引（嵌套场景）。
     */
    private String source;

    /**
     * 本层循环索引变量名（不含 {@code $}），体内用 {@code $u} 引用当前轮下标（0 基）。
     * 留空按嵌套深度默认：最外层 $i、第二层 $j、第三层 $k。
     */
    private String indexVar;
}
