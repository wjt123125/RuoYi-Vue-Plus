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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * CATCH（异常捕获）解析器：{@code CATCH(try块)[.DO(catch块)]}。
 * <p>
 * 结构要点（注意 CATCH 的语义与直觉相反）：
 * <ul>
 *   <li><b>条件位 condition</b> = try 块（getCatchItem），即"要被保护的"那段逻辑；</li>
 *   <li><b>children</b> = [catch块?]（getDoItem），即捕获异常后的处理逻辑，
 *       最多一个元素，可能根本没有（此时用 {@code CATCH({})} 模板而非
 *       {@code CATCH({}).DO({})}）。</li>
 * </ul>
 * 模板在 generateELMethod 里<b>动态选择</b>：children 为空用 CATCH({})，
 * 否则用 CATCH({}).DO({})。
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

    /** EL→JSON：取 CatchCondition 的 catchItem（try 块）作为条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        CatchCondition catchCondition = (CatchCondition) condition;
        Executable catchItem = catchCondition.getCatchItem();
        CmpProperty vo = null;
        if (catchItem instanceof Condition) {
            vo = builderChildVO((Condition) catchItem);
        } else if(catchItem instanceof Node) {
            vo = Optional.of((Node) catchItem).map(nodeMapper).orElse(new CmpProperty());
        }else if(catchItem instanceof Chain){
            Chain chain = (Chain) catchItem;
            vo = buildChildrenChain(chain);
        }
        return vo;
    }

    /**
     * EL→JSON：取 DO 槽位（catch 块），只有两种可能：
     * 1. DO 为空 → 返回空列表；
     * 2. 一个 node 或 一个 condition（或 chain）→ 单元素列表。
     */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        CatchCondition catchCondition = (CatchCondition) condition;
        // 获取 DO 中内容,只有两种可能:
        // 1. DO 为空
        // 2. 一个 node 或者 一个 condition
        Executable catchItem = catchCondition.getDoItem();
        if (Objects.isNull(catchItem)) {
            return Collections.emptyList();
        }

        CmpProperty vo = null;
        if (catchItem instanceof Condition) {
            vo = builderChildVO((Condition) catchItem);
        } else if(catchItem instanceof Node) {
            vo = Optional.of((Node) catchItem).map(nodeMapper).orElse(new CmpProperty());
        }else if(catchItem instanceof Chain){
            Chain chain = (Chain) catchItem;
            vo = buildChildrenChain(chain);
        }
        return new ArrayList<>(Collections.singletonList(vo));
    }

    /** 第 1 步：按有没有 catch 块动态选模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        if (null == jsonEl.getChildren() || jsonEl.getChildren().isEmpty()) {
            return elCatchMethod;
        }
        return elCatchDoMethod;
    }

    /** 第 2 步：填 try 块（第一个 {}），走 generateNodeComponent 兼容子表达式 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getCondition())) {
            return elExpress;
        }
        String catchInternalExpressions = "";
        // CATCH内部表达式
        catchInternalExpressions = generateNodeComponent(jsonEl.getCondition(), catchInternalExpressions);
        // CATCH({}).DO({}) -> CATCH(THEN(a,b)).DO({})
        return StrUtil.replaceFirst(elExpress, "{}", catchInternalExpressions);
    }

    /** 第 3 步：填 catch 块（第二个 {}），DO 里最多一个元素 */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isEmpty(jsonEl.getChildren())) {
            return elExpress;
        }
        // DO({}) 中只会有一个元素,即 children.size()=0
        CmpProperty doItem = jsonEl.getChildren().get(0);
        String doExpressions = "";
        // CATCH内部表达式
        doExpressions = generateNodeComponent(doItem, doExpressions);
        // CATCH(THEN(a,b)).DO({}) -> CATCH(THEN(a,b)).DO(c)
        // CATCH(THEN(a,b)).DO({}) -> CATCH(THEN(a,b)).DO(THEN(c,d))
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
