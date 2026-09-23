package org.dromara.databus.el.bean;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 画布组件树节点（前端画布 JSON 与 EL 互转的中间模型）。
 * <p>
 * 无论是 EL 解析成 JSON，还是 JSON 生成 EL，整棵编排结构都是一棵
 * {@code CmpProperty} 树。理解本类是看懂整个 el 包的前提：
 * <pre>
 * CmpProperty = 一个 EL 语法单元
 * ├── type       : 单元类型。两种取值：
 * │                ① 关键字：THEN / WHEN / SWITCH / FOR / WHILE / ITERATOR /
 * │                   CATCH / AND / OR / NOT / CHAIN（对应 ExpressParserEnum）
 * │                ② 普通节点：LiteFlow NodeTypeEnum 映射类简称，如
 * │                   NodeComponent(普通) / NodeSwitchComponent(选择) /
 * │                   NodeBooleanComponent(布尔) / NodeForComponent(计数) …
 * ├── properties  : 附加属性 id/tag/data（仅节点或显式设置了属性的表达式才有）
 * ├── condition   : "条件位"节点 —— 该表达式的判断/控制节点，例如：
 * │                IF(x).do 中的 x、SWITCH(x).to 中的 x、
 * │                FOR(x).DO 中的 x。
 * │                THEN/WHEN/CATCH/AND/OR/NOT 没有条件位，为 null。
 * └── children   : 子分支列表，例如：
 *                 THEN 的顺序子项、IF 的 [trueCase, falseCase?]、
 *                 SWITCH 的 to 列表、FOR/WHILE/ITERATOR 的 [DO内容, BREAK?]、
 *                 CATCH 的 [try块, catch块?]。
 * </pre>
 * 举例：EL {@code IF(andNode, THEN(a, b), c);} 对应的树：
 * <pre>
 * {type:"IF", condition:{id:"andNode",type:"NodeComponent"}, children:[
 *     {type:"THEN", children:[{id:"a"},{id:"b"}]},
 *     {id:"c", type:"NodeComponent"}
 * ]}
 * </pre>
 * 树的递归结构决定了所有解析器都是递归处理的：父节点调用
 * {@code ParserSelector.getParser(...)} 找到子表达式对应的解析器，逐层转换。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CmpProperty {

    /**
     * 节点/表达式的 id。
     * 注意区分：Condition（THEN/IF 等表达式）默认没有 id，
     * 所以 {@code id == null} 常被当作"这是一个子表达式"的判断依据。
     */
    private String id;

    /**
     * 类型，含义见类注释。生成 EL 时用它选择模板和判断节点种类。
     */
    private String type;

    /**
     * 附加属性（id/tag/data），可为 null。
     */
    private Properties properties;

    /**
     * 条件位节点（IF/SWITCH/FOR/WHILE/ITERATOR 的控制节点），可为 null。
     */
    private CmpProperty condition;

    /**
     * 子分支列表，可为 null 或空。
     */
    private List<CmpProperty> children;

}
