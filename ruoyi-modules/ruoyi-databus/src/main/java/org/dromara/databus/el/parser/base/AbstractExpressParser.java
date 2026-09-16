package org.dromara.databus.el.parser.base;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.selector.ParserSelector;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.element.*;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 解析器抽象基类 —— 双向转换的公共实现，是理解本包<b>最核心</b>的类。
 * <p>
 * 职责分两块：
 * <ol>
 *   <li><b>EL → JSON</b>：提供 Node/Condition/Chain 三种 LiteFlow 元素
 *       转 CmpProperty 的公共逻辑（nodeMapper、builderChildVO、buildChildrenChain）；</li>
 *   <li><b>JSON → EL</b>：实现"模板法五步曲"主干 {@link #abstractGenerateEL}，
 *       并提供递归生成子表达式的 {@link #generateNodeComponent}。</li>
 * </ol>
 * 子类（parser.el 包）只需回答三个问题：
 * 我的模板是什么（generateELMethod）、条件位怎么填（generateCondition）、
 * 子分支怎么填（generateCmp）。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
public abstract class AbstractExpressParser implements ExpressParser {

    /**
     * Condition → 类型字符串 的映射函数。
     * 取 LiteFlow Condition 的 conditionType（如 "then"）并转大写（"THEN"），
     * 取不到时返回空串。
     */
    protected final Function<Condition, String> typeMapper = condition -> Optional.ofNullable(condition)
            .map(Condition::getConditionType)
            .map(ConditionTypeEnum::getType)
            .map(String::toUpperCase)
            .orElse(StringUtils.EMPTY);


    /**
     * Node → CmpProperty 的映射函数（EL→JSON 方向使用频率最高）。
     * <p>
     * 生成形如 {@code {"id":"a","type":"NodeComponent","properties":{...}}} 的单元：
     * <ul>
     *   <li>id：节点的组件编码（画布上节点与 EL 里 a、b、c 的对应物）；</li>
     *   <li>type：LiteFlow NodeTypeEnum 的映射类简称（普通节点是 "NodeComponent"，
     *       选择节点是 "NodeSwitchComponent"……），前端据此渲染不同节点样式；</li>
     *   <li>properties：节点的 tag / cmpData（组件参数）。</li>
     * </ul>
     * 特例：{@link FallbackNode}（降级节点）没有常规 nodeId，用
     * {@code getExpectedNodeId()}（期望降级到的目标节点 id）作为 id。
     */
    protected final Function<Node, CmpProperty> nodeMapper = node -> {
        if (FallbackNode.class.getSimpleName().equals(node.getClass().getSimpleName())) {
            FallbackNode fallbackNode = (FallbackNode) node;
            return CmpProperty.builder()
                    .id(fallbackNode.getExpectedNodeId())
                    .type(fallbackNode.getType().getCode())
                    .properties(getProperties(null, fallbackNode.getTag(), fallbackNode.getCmpData()))
                    .build();
        }
        return CmpProperty.builder()
                .id(node.getId())
                .type(node.getType().getMappingClazz().getSimpleName())
                .properties(getProperties(null, node.getTag(), node.getCmpData()))
                .build();
    };

    /**
     * FallbackNode → CmpProperty 的专用映射函数（逻辑同 nodeMapper 的特例分支）。
     */
    protected final Function<FallbackNode, CmpProperty> fallbackNodeMapper = node ->
            CmpProperty.builder()
                    .id(node.getExpectedNodeId())
                    .type(node.getType().getCode())
                    .properties(getProperties(null, node.getTag(), node.getCmpData()))
                    .build();

    /**
     * EL→JSON：构造本表达式 VO 外壳（各关键字的公共实现）。
     * <ul>
     *   <li>type：由子类 getExpressType 判定的 EL 关键字（THEN/IF/...）；</li>
     *   <li>properties：condition 自身的 id/tag（经过默认 id 过滤，见 getPropertyId）。</li>
     * </ul>
     */
    @Override
    public CmpProperty builderVO(Condition condition) {
        return CmpProperty.builder()
                .id(null)
                .type(this.getExpressType(condition).getType())
                .properties(getProperties(condition.getId(), condition.getTag()))
                .build();
    }

    /**
     * EL→JSON：把"被引用的子编排链"转成 type=CHAIN 的单元。
     * <p>
     * 场景：EL 里出现 {@code SWITCH(a).to(b, subChain);}，其中 subChain 是另一条链。
     * 转换结果形如：
     * <pre>{"id":"subChain","type":"CHAIN","children":[ ...子链内的 condition 列表... ]}</pre>
     * 注意：子链内每个 condition 也要通过 {@link ParserSelector} 找到各自的解析器
     * 递归处理（但只填 type/properties/children，不再展开一层 condition 位）。
     */
    @Override
    public CmpProperty buildChildrenChain(Chain chain){

        CmpProperty chainProperty = CmpProperty.builder()
                .id(chain.getChainId())
                .type(ExpressParserEnum.CHAIN.getType().toUpperCase())
                .properties(getProperties(null, chain.getTag()))
                .build();
        chainProperty.setCondition(null);
        List<CmpProperty> childVos = chain.getConditionList().stream().map(condition -> {
            ExpressParser parser = ParserSelector.getParser(condition);
            CmpProperty vo = CmpProperty.builder()
                    .type(parser.getExpressType(condition).getType())
                    .properties(getProperties(chain.getChainId(), condition.getTag()))
                    .build();
            vo.setChildren(parser.builderChildren(condition));
            return vo;
        }).collect(Collectors.toList());
        chainProperty.setChildren(childVos);
        return chainProperty;
    }

    /**
     * JSON→EL 第 5 步的公共实现：确保表达式以分号结尾（已结尾则不再追加）。
     * 循环类解析器（FOR/WHILE/ITERATOR）会覆盖本方法，先拼 .BREAK(...) 再补分号。
     */
    @Override
    public String generateELEnd(CmpProperty jsonEl, String elExpress) {
        return StrUtil.appendIfMissing(elExpress, elEnd);
    }

    // ==================== properties 构造辅助（EL→JSON） ====================

    /**
     * 两参版：由 condition 的 id + tag 构造 Properties，全空则返回 null
     * （前端拿到 null 就不会渲染属性面板）。
     */
    protected Properties getProperties(String id, String tag) {
        if (null == id && null == tag) {
            return null;
        }
        String propertyId = getPropertyId(id);
        if (null == propertyId && null == tag) {
            return null;
        }
        return new Properties(propertyId, tag, null);
    }

    /**
     * 三参版：节点用，多一个 data（LiteFlow 的组件参数 cmpData）。
     */
    protected Properties getProperties(String id, String tag, String data) {
        if (null == id && null == tag && null == data) {
            return null;
        }
        String propertyId = getPropertyId(id);
        if (null == propertyId && null == tag && null == data) {
            return null;
        }
        return new Properties(propertyId, tag, data);
    }

    /**
     * 过滤 LiteFlow 自动生成的默认 condition id。
     * <p>
     * LiteFlow 解析 EL 时会给 Condition 塞一个默认 id（形如 "condition-then"、
     * "condition-switch"）。这不是用户设置的属性，画布上不应显示，
     * 所以凡是匹配本解析器默认 id 的一律抹成 null。
     */
    protected String getPropertyId(String propertyId) {
        // LF默认id: condition-switch
        if (StrUtil.equals(defaultConditionId(), propertyId)) {
            return null;
        } else {
            return propertyId;
        }
    }

    /**
     * 本解析器对应的 LiteFlow 默认 condition id，如 THEN 解析器 → "condition-then"。
     */
    protected String defaultConditionId() {
        return StrUtil.format("condition-{}", this.parserType().getType().toLowerCase());
    }

    // ==================== 子表达式转换（EL→JSON） ====================

    /**
     * EL→JSON：转换一个"子 Condition"（完整三件套：id/type/properties +
     * condition 位 + children）。父表达式的 builderChildren 遇到子 Condition 时调用。
     * 本身是递归入口：builderCondition/builderChildren 又会触发更深层转换。
     */
    protected CmpProperty builderChildVO(Condition condition) {
        ExpressParser parser = ParserSelector.getParser(condition);
        // id, type, properties
        CmpProperty cmpProperty = parser.builderVO(condition);
        // condition
        cmpProperty.setCondition(parser.builderCondition(condition));
        // children
        cmpProperty.setChildren(parser.builderChildren(condition));
        return cmpProperty;
    }

    /**
     * EL→JSON：遍历一组可执行对象（LiteFlow 的 Executable 统一父类），
     * 按实际类型分派转换：
     * <ul>
     *   <li>Condition → 子表达式，递归 {@link #builderChildVO}；</li>
     *   <li>Node      → 普通节点，走 {@link #nodeMapper}；</li>
     *   <li>Chain     → 被引用的子编排链，走 {@link #buildChildrenChain}。</li>
     * </ul>
     */
    protected void builderChildList(List<Executable> executableList, List<CmpProperty> children) {
        if (CollUtil.isEmpty(executableList)) {
            return;
        }
        executableList.forEach(executable -> {
            CmpProperty vo = null;
            if (executable instanceof Condition) {
                vo = builderChildVO((Condition) executable);
            } else if(executable instanceof Node) {
                vo = Optional.of((Node) executable).map(nodeMapper).orElse(new CmpProperty());
            }else if(executable instanceof Chain){
                Chain chain = (Chain) executable;
                vo = buildChildrenChain(chain);
            }
            children.add(vo);
        });
    }

    // ==================== JSON → EL 主干 ====================

    /**
     * JSON→EL 总入口。
     * <pre>
     * abstractGenerateEL 递归生成主体
     *   → 再按本关键字收尾（循环类在此追加 .BREAK(...)）
     *   → 最后补分号。
     * </pre>
     * 递归发生在第 3 步 generateCmp 内部：子表达式会再次调用 abstractGenerateEL。
     */
    @Override
    public String builderEL(CmpProperty jsonEl) {
        String generateEL = abstractGenerateEL(jsonEl);
        ExpressParser parser = ParserSelector.getParser(jsonEl.getType().toLowerCase());
        // 5.补充分号 THEN(a, b, c).id("dog");
        generateEL = parser.generateELEnd(jsonEl, generateEL);

        return generateEL;
    }

    /**
     * 模板法五步曲 —— 任何 EL 关键字都按同一节奏拼接：
     * <pre>
     * 1. generateELMethod   拿模板：       THEN({})
     * 2. generateCondition  填条件位：     IF({},{}) → IF(a,{})
     * 3. generateCmp        填子分支：     IF(a,{}) → IF(a, THEN(b,c))
     *      ↑ 内部递归：子表达式回到 abstractGenerateEL，直到遇到普通节点
     * 4. generateIdAndTag   拼表达式属性： ... .id("dog").tag("x").data("y")
     * 5. generateELEnd      （见 builderEL，不在本方法内做）
     * </pre>
     */
    protected String abstractGenerateEL(CmpProperty jsonEl) {
        ExpressParser parser = ParserSelector.getParser(jsonEl.getType().toLowerCase());

        // 1.生成外部函数表达式 THEN({})
        String elExpress = parser.generateELMethod(jsonEl);
        // 2.填充EL条件, THEN没有条件, THEN(a, b, c)
        elExpress = parser.generateCondition(jsonEl, elExpress);
        // 3.填充EL组件 THEN(a, b, c)
        elExpress = parser.generateCmp(jsonEl, elExpress);
        // 4.拼接属性 THEN(a, b, c).id("dog")
        elExpress = parser.generateIdAndTag(jsonEl, elExpress);
        // 5.补充分号 THEN(a, b, c).id("dog");
        // elExpress = parser.generateELEnd(elExpress);

        return elExpress;
    }

    /**
     * JSON→EL：把一个 CmpProperty 单元渲染成"可放进括号里的内容"，
     * 是递归的枢纽。两种情况：
     * <ul>
     *   <li><b>id == null → 子表达式</b>（THEN/IF/AND...）：
     *       递归调用 {@link #abstractGenerateEL} 生成整段表达式，
     *       例如 THEN(b,c)、AND(x,y)；</li>
     *   <li><b>id != null → 普通节点</b>：
     *       <ul>
     *         <li>普通节点（type=NodeComponent）：拼 {@code a.tag("x").data("y")}，
     *             tag/data 为 null 时自动跳过；</li>
     *         <li>布尔节点（type=NodeBooleanComponent）：只拼 id（出现在
     *             IF/WHILE 的条件位或 .BREAK(d) 里，布尔节点不允许带 tag/data）。</li>
     *       </ul></li>
     * </ul>
     *
     * @param jsonEl          待渲染单元
     * @param nodeComponentId 已累积的片段（在本片段尾部继续拼接后返回）
     */
    protected String generateNodeComponent(CmpProperty jsonEl, String nodeComponentId) {
        String id = jsonEl.getId();
        if (null == id) {
            // 说明是一个 condition
            nodeComponentId = nodeComponentId + abstractGenerateEL(jsonEl);
        } else {
            // 普通节点处理
            if (StringUtils.equals(NodeTypeEnum.COMMON.getMappingClazz().getSimpleName(), jsonEl.getType())) {
                // 节点组件标签处理 a.tag("dog")
                // String nodeIdAndTag = id + getELNodeTag(jsonEl);
                String nodeIdAndTag = StringUtils.appendIfMissing(id, getELNodeTag(jsonEl));
                nodeIdAndTag = StringUtils.appendIfMissing(nodeIdAndTag, getELNodeData(jsonEl));
                nodeComponentId = nodeComponentId + nodeIdAndTag;
            }
            // 条件节点处理
            else if (StringUtils.equals(NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName(), jsonEl.getType())) {
                nodeComponentId = nodeComponentId + id;
            }
        }
        return nodeComponentId;
    }

    /**
     * 取节点的 tag 片段：有 tag 返回 {@code .tag("xxx")}，否则返回 null（不拼接）。
     */
    protected String getELNodeTag(CmpProperty jsonEl) {
        if (Objects.isNull(jsonEl.getProperties())) {
            return null;
        }
        Properties properties = jsonEl.getProperties();
        if (null == properties.getTag()) {
            return null;
        }
        return StrUtil.format(elNodeTag, properties.getTag());
    }

    /**
     * 取节点的 data 片段：有 data 返回 {@code .data("xxx")}。
     * data 通常是 JSON 字符串，需先做 Java 字符串转义（引号/换行等），
     * 否则会把 EL 表达式的双引号结构破坏掉。
     */
    protected String getELNodeData(CmpProperty jsonEl) {
        if (Objects.isNull(jsonEl.getProperties())) {
            return null;
        }
        Properties properties = jsonEl.getProperties();
        if (null == properties.getData()) {
            return null;
        }
        return StrUtil.format(elNodeData, escapeJava(properties.getData()));
    }

    /**
     * 将字符串转义为可嵌入 EL 表达式双引号内的形式。
     * 等价于原 commons-lang StringEscapeUtils.escapeJava。
     */
    protected static String escapeJava(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

}
