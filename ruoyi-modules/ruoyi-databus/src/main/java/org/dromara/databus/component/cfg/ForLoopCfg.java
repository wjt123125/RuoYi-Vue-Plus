package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * 计数循环组件（forLoop）的节点参数。
 * <pre>
 * { "count": 3 }
 * { "count": "{{ $.request.total }}" }
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
     *     <li>表达式（如 {@code "{{ $.request.total }}"}），resolve 求值取数字，
     *     表达式中可用外层循环索引（嵌套场景，如 {@code "{{ $.groups[$i].count }}"}）。</li>
     * </ul>
     * 0 表示循环体零次执行；不允许负数。
     */
    @DatabusProp(
        label = "循环次数", required = true, widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 1,
        placeholder = "3 或 {{ $.路径 }}",
        description = "整数常量（JSON 高级模式写数字）或 {{ $.路径 }} 表达式；0 表示循环体零次执行，不允许负数"
    )
    private Object count;

    /**
     * 本层循环索引变量名（不含 {@code $}），循环体内用 {@code $row} 引用当前轮下标（0 基）。
     * 留空按嵌套深度默认：最外层 $i、第二层 $j、第三层 $k。
     */
    @DatabusProp(
        label = "下标变量名", order = 2, placeholder = "i",
        description = "字母开头，仅含字母数字下划线；体内用 $变量名 引用当前轮下标；留空按嵌套深度默认 $i/$j/$k"
    )
    private String indexVar;
}
