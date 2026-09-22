package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractLoopExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.IteratorCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * ITERATOR（迭代循环）解析器：{@code ITERATOR(x).DO(...)[.BREAK(d)]}。
 * <p>
 * LiteFlow 的 {@link IteratorCondition} 与 ForCondition 结构同构，
 * 循环条件节点通过 {@link IteratorCondition#getIteratorNode()} 获取，
 * 该节点必须是继承 {@code NodeIteratorComponent} 的迭代组件。
 * DO/BREAK 处理全部复用 {@link AbstractLoopExpressParser}，本类与
 * {@link ForConditionParser} 的差异仅在条件位的取值来源与模板。
 *
 * @author databus
 */
@Component
public class IteratorConditionParser extends AbstractLoopExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_ITERATOR;
    }

    /** EL→JSON：取 IteratorCondition 的 iteratorNode（迭代器节点）作为条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        IteratorCondition iteratorCondition = (IteratorCondition) condition;
        Node iteratorNode = iteratorCondition.getIteratorNode();
        // Node 转 CmpPropertyVO
        return Optional.of(iteratorNode).map(nodeMapper).orElse(new CmpProperty());
    }

    /** 第 1 步：ITERATOR 模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elIteratorMethod;
    }

    /**
     * 第 2 步：填迭代器节点（第一个 {}）。
     * 迭代器是 NodeIteratorComponent 类型的普通节点，必须带 tag（实例唯一/数据空间）
     * 与 data（source/indexVar 参数），故走 appendNodeIdTagData 而非裸 id。
     */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getCondition())) {
            return elExpress;
        }
        CmpProperty condition = jsonEl.getCondition();
        String nodeComponentId = appendNodeIdTagData(condition, "");
        // ITERATOR({}).DO({}) -> ITERATOR(iteratorLoop.tag("iteratorLoop1").data("{...}")).DO({})
        return StrUtil.replaceFirst(elExpress, "{}", nodeComponentId);
    }

    /** 第 3 步：填 DO 内容（第二个 {}），排除 BREAK 后递归生成 */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isEmpty(jsonEl.getChildren())) {
            return elExpress;
        }
        // 获取 DO({}) 内部的组件
        CmpProperty doExpressVO = nonBreakMapper(jsonEl.getChildren());
        // 生成 DO({}) 内部的表达式 -> THEN(b,c)
        String doEL = generateDoEL(doExpressVO);
        // 填充EL组件, ITERATOR(a).DO({}) -> ITERATOR(a).DO(THEN(b,c))
        return StrUtil.format(elExpress, doEL);
    }

    /** 第 4 步：拼表达式级 id/tag/data */
    @Override
    public String generateIdAndTag(CmpProperty jsonEl, String elExpress) {
        Properties properties = jsonEl.getProperties();
        if (Objects.isNull(properties)) {
            return elExpress;
        }

        // 该表达式的 id 或者 tag
        String expressIdAndTag = "";
        if (StringUtils.isNotEmpty(properties.getId())) {
            expressIdAndTag = StrUtil.format(elExpressId, properties.getId());
        }
        if (StringUtils.isNotEmpty(properties.getTag())) {
            expressIdAndTag = expressIdAndTag + StrUtil.format(elExpressTag, properties.getTag());
        }
        if (StringUtils.isNotEmpty(properties.getData())) {
            expressIdAndTag = expressIdAndTag + StrUtil.format(elExpressData, properties.getData());
        }
        return StrUtil.appendIfMissing(elExpress, expressIdAndTag);
    }

    /** 收尾：追加 .BREAK(布尔节点)，与 FOR/WHILE 的实现同构 */
    @Override
    public String generateBreak(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getChildren()) || jsonEl.getChildren().isEmpty()) {
            return elExpress;
        }

        CmpProperty breakVO = breakMapper(jsonEl.getChildren());
        if (Objects.isNull(breakVO)) {
            return elExpress;
        }

        CmpProperty breakNode = foundBreakNode(breakVO.getChildren());
        if (Objects.isNull(breakNode)) {
            return elExpress;
        }

        // 循环跳出 BREAK 语句处理
        if (StringUtils.equals(NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName(), breakNode.getType())) {
            // .BREAK({}) -> .BREAK(d)
            String breakEL = StrUtil.format(elBreakMethod, breakNode.getId());
            // 补充 break 语句: ITERATOR(a).DO(THEN(b,c)) -> ITERATOR(a).DO(THEN(b,c)).BREAK(d)
            elExpress = elExpress + breakEL;
        }
        return elExpress;
    }

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.ITERATOR;
    }
}
