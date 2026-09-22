package org.dromara.databus.component.flow;

import lombok.Data;

import java.util.List;

/**
 * 选择路由组件（switchRoute）的节点参数。
 * <pre>
 * {
 *   "source": "$.request.type",
 *   "cases": [
 *     { "value": "A", "target": "caseA" },
 *     { "value": "$.request.vipCode", "target": "caseB" }
 *   ]
 * }
 * </pre>
 *
 * @author databus
 */
@Data
public class SwitchRouteCfg {

    /**
     * 判断值的 JSONPath：从上下文读出实际值，逐条与 {@link CaseRoute#getValue()} 比较。
     */
    private String source;

    /**
     * 值 → 分支映射，按数组顺序逐条匹配，命中第一条即跳转。
     */
    private List<CaseRoute> cases;

    /**
     * 一条分支映射。
     */
    @Data
    public static class CaseRoute {

        /**
         * 比较值：常量（字符串/数字/布尔），或 {@code $.xxx} 路径 /
         * {@code $i} 索引占位符（执行前经上下文解析）。
         * 两个数字按数值比较（Integer 200 与 Long 200 视为相等）。
         */
        private Object value;

        /**
         * 命中后跳转的画布分支名：对应 SWITCH 算子该分支的 outletLabel（case 名），
         * 组件返回 {@code ":target"} 由 LiteFlow 按表达式 tag 命中。
         * 分支为单节点时 EL 生成器自动包一层 THEN 挂 tag，节点自身的数据空间 tag 不受影响。
         */
        private String target;
    }
}
