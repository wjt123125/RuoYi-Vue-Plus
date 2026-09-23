package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.CatchCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CATCH（异常捕获）解析器：{@code CATCH(try块)[.DO(catch块)]}。
 * <p>
 * 结构要点（与 WHEN/AND/OR 等自建网关一致，无独立 condition 位）：
 * <ul>
 *   <li><b>children[0]</b> = try 块（LiteFlow 的 catchItem），即"要被保护的"那段逻辑；</li>
 *   <li><b>children[1]</b> = catch 块（LiteFlow 的 doItem），即捕获异常后的处理逻辑，
 *       可能不存在（此时用 {@code CATCH({})} 模板而非 {@code CATCH({}).DO({})}）。</li>
 * </ul>
 * 模板在 generateELMethod 里<b>动态选择</b>：catch 块（children[1]）存在用
 * CATCH({}).DO({})，否则用 CATCH({})。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/6/21
 */
@Component
public class CatchConditionParser extends AbstractExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_CATCH;
    }

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.CATCH;
    }

    /**
     * EL→JSON：CATCH 无独立 condition 位（与 WHEN/AND/OR 等自建网关一致），
     * try 块和 catch 块统一放在 children：children[0]=try，children[1]=catch。
     */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return null;
    }

    /**
     * EL→JSON：children[0]=try 块（catchItem），children[1]=catch 块（doItem）。
     * 任一为 null 则对应位置不产出（保持稀疏）。
     */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        CatchCondition catchCondition = (CatchCondition) condition;
        List<CmpProperty> children = new ArrayList<>();
        // try 块 → children[0]
        Executable catchItem = catchCondition.getCatchItem();
        if (Objects.nonNull(catchItem)) {
            children.add(toCmpProperty(catchItem));
        }
        // catch 块 → children[1]
        Executable doItem = catchCondition.getDoItem();
        if (Objects.nonNull(doItem)) {
            children.add(toCmpProperty(doItem));
        }
        return children;
    }

    /** Executable → CmpProperty 的公共分派（Condition/Node/Chain） */
    private CmpProperty toCmpProperty(Executable executable) {
        if (executable instanceof Condition) {
            return builderChildVO((Condition) executable);
        } else if (executable instanceof Node) {
            return Optional.of((Node) executable).map(nodeMapper).orElse(new CmpProperty());
        } else if (executable instanceof Chain) {
            return buildChildrenChain((Chain) executable);
        }
        return new CmpProperty();
    }

    /** 第 1 步：按有没有 catch 块（children[1]）动态选模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        List<CmpProperty> children = jsonEl.getChildren();
        // children[0]=try, children[1]=catch；只有 catch 块存在时才用 .DO({}) 模板
        if (children != null && children.size() >= 2 && Objects.nonNull(children.get(1))) {
            return elCatchDoMethod;
        }
        return elCatchMethod;
    }

    /** 第 2 步：填 try 块（children[0]）到第一个 {} */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        List<CmpProperty> children = jsonEl.getChildren();
        if (CollectionUtil.isEmpty(children) || Objects.isNull(children.get(0))) {
            return elExpress;
        }
        String tryExpressions = generateNodeComponent(children.get(0), "");
        // CATCH({}).DO({}) -> CATCH(THEN(a,b)).DO({})
        return StrUtil.replaceFirst(elExpress, "{}", tryExpressions);
    }

    /** 第 3 步：填 catch 块（children[1]）到第二个 {} */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        List<CmpProperty> children = jsonEl.getChildren();
        if (children == null || children.size() < 2 || Objects.isNull(children.get(1))) {
            return elExpress;
        }
        String doExpressions = generateNodeComponent(children.get(1), "");
        // CATCH(THEN(a,b)).DO({}) -> CATCH(THEN(a,b)).DO(c)
        return StrUtil.format(elExpress, doExpressions);
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
}
