package org.dromara.databus.el.parser.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import com.yomahub.liteflow.common.ChainConstant;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.ConditionKey;
import com.yomahub.liteflow.flow.element.condition.LoopCondition;
import org.apache.commons.lang3.StringUtils;
import org.dromara.databus.el.parser.el.ForConditionParser;
import org.dromara.databus.el.parser.el.WhileConditionParser;

import java.util.*;
import java.util.function.BiPredicate;


/**
 * 循环类 Condition 的抽象基类 —— FOR / WHILE / ITERATOR 三种循环的公共逻辑。
 * @see ForConditionParser
 * @see WhileConditionParser
 * @see IteratorConditionParser
 * <p>
 * 三种循环的 EL 形态完全同构：{@code XXX(循环条件).DO(循环体)[.BREAK(布尔节点)]}，
 * 差异只在"循环条件"节点的类型（计数器 / 布尔条件 / 迭代器）。因此本基类包办：
 * <ul>
 *   <li><b>EL→JSON</b>：从 LiteFlow 的 {@link LoopCondition#getExecutableGroup()}
 *       按 key 取出 DO 块和 BREAK 节点，转成 children =
 *       [DO内容, BREAK包装节点(可选)]；</li>
 *   <li><b>JSON→EL</b>：generateCmp 里用 {@link #nonBreakMapper} 挑出 DO 内容递归生成，
 *       收尾时用 {@link #breakMapper} 挑出 BREAK 包装节点，追加 {@code .BREAK(d)}。</li>
 * </ul>
 * <p>
 * 关于 LiteFlow 的 executableGroup：循环 Condition 内部的可执行对象不是平铺的
 * executableList，而是按角色分组的 Map，key 见 {@link ConditionKey}
 * （如 DO_KEY、BREAK_KEY）。这是循环类与 THEN/IF 等普通类解析器的根本差异。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/24
 */
public abstract class AbstractLoopExpressParser extends AbstractExpressParser {

    /**
     * 当前正在解析的循环 Condition 的角色分组视图（DO/BREAK 等）。
     * 由 builderChildren 每次进入时刷新（有状态，但解析是单线程串行的）。
     */
    private Map<String, List<Executable>> executableGroup = new HashMap<>();

    /**
     * EL→JSON：循环类的 children 构造。
     * <p>
     * LiteFlow 循环 Condition 的 DO 槽位只放<b>一个</b>可执行对象
     * （可能是 Node / Condition / Chain），所以这里的"列表"其实最多两个元素：
     * <pre>
     * children = [ DO内容?, BREAK包装节点? ]
     * </pre>
     * BREAK 特殊：它不是直接放布尔节点，而是包一层 type=BREAK 的 CmpProperty，
     * 布尔节点挂在其 children 里（与 EL 的 .BREAK(d) 结构对齐）。
     */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();
        LoopCondition loopCondition = (LoopCondition) condition;
        this.executableGroup = loopCondition.getExecutableGroup();

        // 获得要循环的可执行对象（DO 槽位）
        Executable executableItem = this.getDoExecutor();
        // 可执行对象不为空，则去执行
        if (ObjectUtil.isNotNull(executableItem)) {
            CmpProperty vo = null;
            if (executableItem instanceof Condition) {
                vo = builderChildVO((Condition) executableItem);
            } else if(executableItem instanceof Node) {
                vo = Optional.of((Node) executableItem).map(nodeMapper).orElse(new CmpProperty());
            }else if(executableItem instanceof Chain){
                Chain chain = (Chain) executableItem;
                vo = buildChildrenChain(chain);
            }
            children.add(vo);
        }

        // 获取Break节点
        Executable breakItem = this.getBreakItem();
        // 如果break组件不为空，则去执行
        if (ObjectUtil.isNotNull(breakItem)) {
            if (breakItem instanceof Node) {
                // type: BREAK —— 包一层 BREAK 包装节点，布尔节点放 children
                CmpProperty breakVO = new CmpProperty();
                breakVO.setType(ChainConstant.BREAK);
                List<CmpProperty> breakChildren = new ArrayList<>();
                CmpProperty vo = Optional.of((Node) breakItem).map(nodeMapper).orElse(new CmpProperty());
                breakChildren.add(vo);
                breakVO.setChildren(breakChildren);

                children.add(breakVO);
            }
        }
        return children;
    }

    /** 取 BREAK 槽位的可执行对象（循环跳出条件的布尔节点） */
    protected Executable getBreakItem() {
        return this.getExecutableOne(ConditionKey.BREAK_KEY);
    }

    /** 取 DO 槽位的可执行对象（循环体） */
    protected Executable getDoExecutor() {
        return this.getExecutableOne(ConditionKey.DO_KEY);
    }

    /** 按角色 key 取分组列表的第一个（循环的 DO/BREAK 槽位都只有一个元素） */
    protected Executable getExecutableOne(String groupKey) {
        List<Executable> list = getExecutableList(groupKey);
        if (CollUtil.isEmpty(list)) {
            return null;
        } else {
            return list.get(0);
        }
    }

    /** 按角色 key 取分组列表，无则返回空列表 */
    protected List<Executable> getExecutableList(String groupKey) {
        List<Executable> executableList = this.executableGroup.get(groupKey);
        if (CollUtil.isEmpty(executableList)) {
            executableList = new ArrayList<>();
        }
        return executableList;
    }

    /**
     * JSON→EL 收尾（覆盖父类）：循环表达式在补分号之前，
     * 先尝试追加 {@code .BREAK(布尔节点)}，即
     * {@code FOR(a).DO(THEN(b,c))} → {@code FOR(a).DO(THEN(b,c)).BREAK(d);}。
     */
    @Override
    public String generateELEnd(CmpProperty jsonEl, String elExpress) {
        elExpress = generateBreak(jsonEl, elExpress);
        return StrUtil.appendIfMissing(elExpress, elEnd);
    }

    /**
     * 生成 DO({}) 内部的表达式。
     * <p>
     * DO 内容有两种形态，必须走 {@link #generateNodeComponent} 枢纽分派，
     * 不能直接 {@link #abstractGenerateEL}：
     * <ul>
     *   <li>普通节点（{@code id != null}，如 {@code FOR(x).DO(a.tag("a1").data(...))}）：
     *       拼 {@code id.tag.data} 片段；节点类型（NodeComponent 等）在
     *       ParserSelector 中没有也不应有关键字解析器，直接递归五步曲会因
     *       找不到解析器抛 RuntimeException；</li>
     *   <li>子表达式（{@code id == null}，如 {@code FOR(x).DO(THEN(b,c))}）：
     *       枢纽内部会自行递归"模板法五步曲"。</li>
     * </ul>
     */
    protected String generateDoEL(CmpProperty doExpressVO) {
        if (doExpressVO == null) {
            return "";
        }
        return generateNodeComponent(doExpressVO, "");
    }

    /**
     * 从 children 里挑出 type=BREAK 的包装节点；没有 BREAK 返回 null。
     */
    protected CmpProperty breakMapper(List<CmpProperty> voList) {
        return voList.stream()
                .filter(vo -> breakMatcher.test(vo.getType(), ChainConstant.BREAK))
                .findFirst()
                .orElse(null);
    }

    /**
     * 从 children 里挑出非 BREAK 的第一个（即 DO 内容）。
     */
    protected CmpProperty nonBreakMapper(List<CmpProperty> voList) {
        return voList.stream()
                .filter(vo -> !breakMatcher.test(vo.getType(), ChainConstant.BREAK))
                .findFirst()
                .orElse(null);
    }

    // 筛选 BREAK 类型  Matcher
    protected final BiPredicate<String, String> breakMatcher = StringUtils::equals;


    /**
     * 子类实现的 BREAK 追加逻辑（三种循环的写法一致，但保留扩展点）。
     */
    public abstract String generateBreak(CmpProperty jsonEl, String elExpress);

    /**
     * 取 BREAK 包装节点内的第一个子节点（即 .BREAK(d) 里的那个布尔节点 d）。
     */
    protected CmpProperty foundBreakNode(List<CmpProperty> children) {
        if (CollUtil.isEmpty(children)) {
            return null;
        } else {
            return children.get(0);
        }
    }
}
