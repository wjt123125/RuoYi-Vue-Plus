package org.dromara.databus.el.parser.el;

import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractAndOrNotExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.NotCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * NOT（布尔非）解析器：{@code NOT(a)}。
 * <p>
 * 与 AND/OR 同属布尔运算家族（父类 {@link AbstractAndOrNotExpressParser}），
 * 差异：NOT 只允许 1 个子项（generateCmp 里有数量断言）。
 * 常嵌套使用：{@code IF(NOT(AND(a,b)), c)}。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/24
 */
@Component
public class NotConditionParser extends AbstractAndOrNotExpressParser {

    /** 注册 key：NOT 专属的 TYPE_NOT_OPT 类型 */
    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_NOT_OPT;
    }

    /** NOT 没有条件位，返回 null */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return null;
    }

    /** EL→JSON：取唯一的子项（Condition→递归 / Node→布尔节点） */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();

        NotCondition notCondition = (NotCondition) condition;
        Executable notConditionItem = notCondition.getItem();
        CmpProperty vo = null;
        if (notConditionItem instanceof Condition) {
            // 这里解析器有: AndOrConditionParser、NotConditionParser
            vo = builderChildVO((Condition) notConditionItem);
        } else if(notConditionItem instanceof Node) {
            vo = Optional.of((Node) notConditionItem).map(nodeMapper).orElse(new CmpProperty());
        }

        children.add(vo);
        return children;
    }

    /** 第 1 步：NOT 模板 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elNotMethod;
    }

    /** 第 2 步：NOT 没有条件位，模板原样返回 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        // 填充EL条件, NOT没有条件, NOT({})
        return elExpress;
    }

    /** 第 3 步：填唯一的子项（数量必须为 1） */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        List<CmpProperty> children = jsonEl.getChildren();
        // NOT的大小一定是:1
        if (1 != children.size()) {
            return elExpress;
        }
        String nodeComponentId = "";
        nodeComponentId = generateNodeComponent(children.get(0), nodeComponentId);
        // 填充EL组件, NOT({}) -> NOT(a)
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

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.NOT;
    }
}
