package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * WHEN（并行编排）解析器：{@code WHEN(a, b, c)}，多个分支并行执行。
 * <p>
 * 与 {@link ThenConditionParser} 几乎完全一致（模板、拼接逻辑都相同），
 * 仅有的差异：
 * <ul>
 *   <li>注册类型是 TYPE_WHEN；</li>
 *   <li>builderCondition 返回<b>空对象</b>而非 null（序列化后 JSON 是
 *       {@code "condition": {}}，前端可据此区分渲染，行为上等价于"无条件位"）。</li>
 * </ul>
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/23
 */
@Component
public class WhenConditionParser extends AbstractExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_WHEN;
    }

    /** WHEN 没有条件位，返回空对象（区别于 THEN 的 null，见类注释） */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return new CmpProperty();
    }

    /** EL→JSON：与 THEN 相同的三分遍历（Condition/Node/Chain），复用父类 builderChildList */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();
        builderChildList(condition.getExecutableList(), children);
        return children;
    }

    /** 第 1 步：WHEN 的模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elWhenMethod;
    }

    /** 第 2 步：WHEN 没有条件位，模板原样返回 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        // 填充EL条件, WHEN没有条件, WHEN({})
        return elExpress;
    }

    /** 第 3 步：与 THEN 相同的"逐项拼 + 掐尾逗号 + format"节奏 */
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
        return ExpressParserEnum.WHEN;
    }
}
