package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractLoopExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.WhileCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * WHILE（条件循环）解析器：{@code WHILE(x).DO(...)[.BREAK(d)]}。
 * <p>
 * 继承 {@link AbstractLoopExpressParser}，与 {@link ForConditionParser}
 * 的差异仅在"条件位"：
 * <ul>
 *   <li>条件位 x 可以是布尔组件，也可以是 AND/OR/NOT 子表达式
 *       （{@code WHILE(AND(a,b)).DO(...)}），所以 generateCondition 里
 *       有和 IF 一样的"布尔节点 / 子表达式"双分支判断；</li>
 *   <li>DO/BREAK 的处理与 FOR 完全一致（基类 + 本类同构实现）。</li>
 * </ul>
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/23
 */
@Component
public class WhileConditionParser extends AbstractLoopExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_WHILE;
    }

    /** EL→JSON：取 WhileCondition 的 whileItem（布尔节点或 AND/OR/NOT 表达式）作为条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        WhileCondition whileCondition = (WhileCondition) condition;
        Executable whileItem = whileCondition.getWhileItem();
        CmpProperty vo = null;
        if (whileItem instanceof Condition) {
            // 这里解析器有: AndOrConditionParser、NotConditionParser
            vo = builderChildVO((Condition) whileItem);
        } else if(whileItem instanceof Node) {
            vo = Optional.of((Node) whileItem).map(nodeMapper).orElse(new CmpProperty());
        }else if(whileItem instanceof Chain){
            Chain chain = (Chain) whileItem;
            vo = buildChildrenChain(chain);
        }
        return vo;
    }

    /** 第 1 步：WHILE 模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elWhileMethod;
    }

    /**
     * 第 2 步：填循环条件（第一个 {}）。
     * 布尔节点直接用 id；AND/OR/NOT 子表达式递归生成。
     */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getCondition())) {
            return elExpress;
        }
        CmpProperty condition = jsonEl.getCondition();

        String nodeComponentId = "";
        // 没有使用与或非表达式: AND,OR,NOT
        if (StringUtils.equals(NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName(), condition.getType())) {
            nodeComponentId = condition.getId();
        }
        // 使用与或非表达式: AND,OR,NOT
        else {
            nodeComponentId = generateNodeComponent(condition, nodeComponentId);
        }

        // WHILE({}).DO({}) -> WHILE(a).DO({})
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
        // 填充EL组件, WHILE(a).DO({}) -> WHILE(a).DO(THEN(b,c))
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

    /** 收尾：追加 .BREAK(布尔节点)，与 FOR 的实现同构 */
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
            // 补充 break 语句: WHILE(a).DO(THEN(b,c)) -> WHILE(a).DO(THEN(b,c)).BREAK(d)
            elExpress = elExpress + breakEL;
        }
        return elExpress;
    }

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.WHILE;
    }
}
