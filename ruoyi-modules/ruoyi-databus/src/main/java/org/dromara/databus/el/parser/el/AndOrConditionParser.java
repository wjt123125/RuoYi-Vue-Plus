package org.dromara.databus.el.parser.el;

import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractAndOrNotExpressParser;
import com.yomahub.liteflow.common.ChainConstant;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.AndOrCondition;
import com.yomahub.liteflow.flow.element.condition.BooleanConditionTypeEnum;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * AND / OR（布尔与/或）共用解析器：{@code AND(a, b)}、{@code OR(a, b)}。
 * <p>
 * LiteFlow 里 AND 和 OR 是同一个 Condition 类型（TYPE_AND_OR_OPT），
 * 区分靠 {@link AndOrCondition#getBooleanConditionType()}（AND 或 OR），
 * 所以本类"一名两用"：
 * <ul>
 *   <li>EL→JSON：getExpressType 运行时判断返回 AND 或 OR 枚举；</li>
 *   <li>JSON→EL：generateELMethod 按 jsonEl.type 选 AND({}) 或 OR({}) 模板。</li>
 * </ul>
 * 结构上 AND/OR 的子项必须恰好 2 个（generateCmp 里有数量断言），
 * 子项可以是布尔节点，也可以是嵌套的 AND/OR/NOT 子表达式。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/24
 */
@Component
public class AndOrConditionParser extends AbstractAndOrNotExpressParser {

    /** 注册 key：AND/OR 共用的组合类型 */
    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_AND_OR_OPT;
    }

    /** AND/OR 没有条件位，返回 null */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return null;
    }

    /** EL→JSON：遍历 2 个子项（Condition→递归 / Node→布尔节点） */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();

        AndOrCondition andOrCondition = (AndOrCondition) condition;
        List<Executable> andOrConditionItem = andOrCondition.getItem();
        andOrConditionItem.forEach(andOrItem -> {
            CmpProperty vo = null;
            if (andOrItem instanceof Condition) {
                // 这里解析器有: AndOrConditionParser、NotConditionParser
                vo = builderChildVO((Condition) andOrItem);
            } else if(andOrItem instanceof Node) {
                vo = Optional.of((Node) andOrItem).map(nodeMapper).orElse(new CmpProperty());
            }
            children.add(vo);
        });
        return children;
    }

    /** 第 1 步：按 type 选 AND 或 OR 模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        String type = ExpressParserEnum.of(jsonEl.getType()).getType();
        if (ChainConstant.AND.equals(type)) {
            return elAndMethod;
        } else {
            return elOrMethod;
        }
    }

    /** 第 2 步：AND/OR 没有条件位，模板原样返回 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        // 填充EL条件, AND,OR没有条件, AND({})
        return elExpress;
    }

    /** 第 3 步：填 2 个子项（数量必须为 2），"逐项拼 + 掐尾逗号"节奏 */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        List<CmpProperty> children = jsonEl.getChildren();
        // AND,OR的大小一定是2
        if (2 != children.size()) {
            return elExpress;
        }

        String nodeComponentId = "";
        for (CmpProperty vo : children) {
            nodeComponentId = generateNodeComponent(vo, nodeComponentId);
            // 这里会多拼接一个逗号
            nodeComponentId = StrUtil.appendIfMissing(nodeComponentId, elSeparate);
        }
        // 去除多的逗号
        nodeComponentId = StringUtils.substringBeforeLast(nodeComponentId, elSeparate);
        // 填充EL组件, AND({}) -> AND(a,b)
        return StrUtil.format(elExpress, nodeComponentId);
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
     * EL→JSON：运行时判断这个组合类型 Condition 到底是 AND 还是 OR。
     */
    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        AndOrCondition andOrCondition = (AndOrCondition) condition;
        BooleanConditionTypeEnum booleanConditionType = andOrCondition.getBooleanConditionType();
        return ExpressParserEnum.of(booleanConditionType.name());
    }
}
