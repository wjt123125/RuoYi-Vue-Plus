package org.dromara.databus.component.flow;

import lombok.Data;

/**
 * 计数循环组件（forLoop）的节点参数。
 * <pre>
 * { "count": 3 }
 * { "count": "$.request.total" }
 * { "count": 3, "indexVar": "row" }
 * </pre>
 *
 * @author databus
 */
@Data
public class ForLoopCfg {

    /**
     * 循环次数。两种写法：
     * <ul>
     *     <li>数字常量（如 {@code 3}，JSON number）；</li>
     *     <li>字符串纯数字（如 {@code "3"}）；</li>
     *     <li>JSONPath 字符串（如 {@code "$.request.total"}），从上下文读取数字，
     *     路径中可用外层循环索引（嵌套场景，如 {@code "$.groups[$i].count"}）。</li>
     * </ul>
     * 0 表示循环体零次执行；不允许负数。
     */
    private Object count;

    /**
     * 本层循环索引变量名（不含 {@code $}），循环体内用 {@code $row} 引用当前轮下标（0 基）。
     * 留空按嵌套深度默认：最外层 $i、第二层 $j、第三层 $k。
     */
    private String indexVar;
}
