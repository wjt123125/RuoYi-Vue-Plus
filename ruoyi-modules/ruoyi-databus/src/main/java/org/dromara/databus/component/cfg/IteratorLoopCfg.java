package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;

/**
 * 迭代循环组件（iteratorLoop）的节点参数。
 * <pre>
 * { "source": "{{ $.request.items }}" }
 * { "source": "{{ $.groups[$i].users }}", "indexVar": "u" }
 * </pre>
 *
 * @author databus
 */
@Data
public class IteratorLoopCfg {

    /**
     * 被迭代数据的表达式（要数据，{@code {{ $.路径 }}}，resolve 求值）：值必须是数组或集合
     * （{@code List}/{@code Collection}/Java 数组/{@code Iterable}）；值为 null 时按空集合处理
     * （循环体零次执行）。表达式中可用外层循环索引（嵌套场景）。
     */
    @DatabusProp(
        label = "迭代数组表达式", required = true, exprRole = ExprRole.DATA,
        order = 1, placeholder = "{{ $.数据空间.数组字段 }}",
        description = "求值结果必须是数组/集合；null 按空集合处理（循环体零次执行）"
    )
    private String source;

    /**
     * 本层循环索引变量名（不含 {@code $}），体内用 {@code $u} 引用当前轮下标（0 基）。
     * 留空按嵌套深度默认：最外层 $i、第二层 $j、第三层 $k。
     */
    @DatabusProp(
        label = "下标变量名", order = 2, placeholder = "i",
        description = "字母开头，仅含字母数字下划线；体内用 $变量名 引用当前轮下标；留空按嵌套深度默认 $i/$j/$k"
    )
    private String indexVar;
}
