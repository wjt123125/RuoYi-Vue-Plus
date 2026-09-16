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
import com.yomahub.liteflow.flow.element.condition.ForCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

/**
 * FOR（计数循环）解析器：{@code FOR(x).DO(...)[.BREAK(d)]}。
 * <p>
 * 继承 {@link AbstractLoopExpressParser}，DO/BREAK 的公共处理都在基类，
 * 本类只负责 FOR 特有的部分：
 * <ul>
 *   <li>条件位 = 计数器节点 x（type=NodeForComponent），指定循环次数；</li>
 *   <li>generateCmp：取 DO 内容（非 BREAK 的第一个 child）递归生成，
 *       例如 {@code FOR(a).DO(THEN(b,c))}。</li>
 * </ul>
 * 注意：children = [DO内容, BREAK包装节点?]，BREAK 由基类的
 * generateELEnd → generateBreak 统一追加。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/23
 */
@Component
public class ForConditionParser extends AbstractLoopExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_FOR;
    }

    /** EL→JSON：取 ForCondition 的 forNode（计数器节点）作为条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        ForCondition forCondition = (ForCondition) condition;
        Node forNode = forCondition.getForNode();
        // Node 转 CmpPropertyVO
        return Optional.of(forNode).map(nodeMapper).orElse(new CmpProperty());
    }


    /** 第 1 步：FOR 模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elForMethod;
    }

    /** 第 2 步：填计数器节点 id（第一个 {}） */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getCondition())) {
            return elExpress;
        }
        CmpProperty condition = jsonEl.getCondition();
        return StrUtil.replaceFirst(elExpress, "{}", condition.getId());
    }

    /** 第 3 步：填 DO 内容（第二个 {}）——递归生成循环体表达式 */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isEmpty(jsonEl.getChildren())) {
            return elExpress;
        }
        // 获取 DO({}) 内部的组件（排除 BREAK）
        CmpProperty doExpressVO = nonBreakMapper(jsonEl.getChildren());
        // 生成 DO({}) 内部的表达式 -> THEN(b,c)
        String doEL = generateDoEL(doExpressVO);
        // 填充EL组件, FOR(a).DO({}) -> FOR(a).DO(THEN(b,c))
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

    /**
     * 收尾：追加 .BREAK(布尔节点)。仅当 BREAK 包装节点内的子节点是
     * 布尔组件（NodeBooleanComponent）时才拼接。
     */
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
            // 补充 break 语句: FOR(a).DO(THEN(b,c)) -> FOR(a).DO(THEN(b,c)).BREAK(d)
            elExpress = elExpress + breakEL;
        }
        return elExpress;
    }

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.FOR;
    }
}
