package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractExpressParser;
import com.yomahub.liteflow.common.ChainConstant;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * THEN（串行编排）解析器：{@code THEN(a, b, c)}，从左到右依次执行。
 * <p>
 * 特点：THEN 是所有解析器里最"简单"的一个——
 * <ul>
 *   <li>没有条件位：builderCondition 返回 null（EL 里 THEN 不带控制节点）；</li>
 *   <li>模板只有一个占位符：{@code THEN({})}，generateCondition 原样返回，
 *       全部内容都由 generateCmp 填充。</li>
 * </ul>
 * JSON→EL 的拼接节奏（其它"列表型"关键字 WHEN/AND/OR 与此一致）：
 * <pre>
 * 逐个子项 generateNodeComponent（子表达式递归 / 节点拼 id.tag.data）
 *   → 每项后面补逗号 → 掐掉最后一个多余逗号 → StrUtil.format 填进 THEN({})
 * </pre>
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Component
public class ThenConditionParser extends AbstractExpressParser {

    private final String type = ChainConstant.THEN;

    /**
     * 注册 key：LiteFlow 的 then 类型。
     * @see ChainConstant
     * @see ConditionTypeEnum
     * */
    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_THEN;
    }

    /** THEN 没有条件位，返回 null */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return null;
    }

    /**
     * EL→JSON：遍历 THEN 内部的可执行对象列表。
     * 每个 executable 按实际类型三分：Condition→递归子表达式、Node→普通节点、Chain→子链。
     */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();
        List<Executable> executableList = condition.getExecutableList();
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
        return children;
    }

    /** 第 1 步：THEN 的模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elThenMethod;
    }

    /** 第 2 步：THEN 没有条件位，模板原样返回 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        // 填充EL条件, THEN没有条件, THEN({})
        return elExpress;
    }

    /** 第 3 步：把所有子项拼成 "a, b, c" 填入 THEN({}) */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isNotEmpty(jsonEl.getChildren())) {
            String nodeComponentId = "";
            for (CmpProperty child : jsonEl.getChildren()) {
                nodeComponentId = generateNodeComponent(child, nodeComponentId);
                // 这里会多拼接一个逗号
                nodeComponentId = StrUtil.appendIfMissing(nodeComponentId, elSeparate);
            }
            // 去除多的逗号
            nodeComponentId = StringUtils.substringBeforeLast(nodeComponentId, elSeparate);
            // 填充EL组件, THEN({}) -> THEN(a, b, c)
            elExpress = StrUtil.format(elExpress, nodeComponentId);
        }
        return elExpress;
    }

    /** 第 4 步：拼表达式级 id/tag/data（THEN(a,b).id("dog") 的部分） */
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


    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.THEN;
    }
}
