package org.dromara.databus.el.parser.el;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractExpressParser;
import com.yomahub.liteflow.common.ChainConstant;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.flow.element.Executable;
import com.yomahub.liteflow.flow.element.Node;
import com.yomahub.liteflow.flow.element.condition.IfCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * IF（条件分支）解析器：{@code IF(x, true分支[, false分支])}。
 * <p>
 * 结构要点（对应两个"槽位"）：
 * <ul>
 *   <li><b>条件位 condition</b> = 判断节点 x：可以是布尔组件
 *       （type=NodeBooleanComponent），也可以是 AND/OR/NOT 子表达式
 *       （{@code IF(AND(a,b), ...)}）；</li>
 *   <li><b>children</b> = [trueCase, falseCase?]：真分支必有，假分支可选
 *       （没有 ELSE 时 children 只有一个元素，生成二元 IF）。</li>
 * </ul>
 * JSON→EL 时模板 {@code IF({},{})} 的两个 {} 分别由第 2、3 步填充：
 * <pre>
 * IF({},{}) → IF(a,{}) → IF(a, b, c)      （三元：有假分支）
 * IF({},{}) → IF(a,{}) → IF(a, b)         （二元：无假分支）
 * </pre>
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/23
 */
@Component
public class IfConditionParser extends AbstractExpressParser {

    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_IF;
    }

    /**
     * EL→JSON：取 IfCondition 的 ifItem（判断节点）作为条件位。
     * 判断节点两种可能：Node（布尔组件）或 Condition（AND/OR/NOT 子表达式）。
     */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        IfCondition ifCondition = (IfCondition) condition;
        Executable ifItem = ifCondition.getIfItem();
        CmpProperty vo = null;
        if (ifItem instanceof Condition) {
            // 这里解析器有: AndConditionParser、OrConditionParser、NotConditionParser
            vo = builderChildVO((Condition) ifItem);
        } else if(ifItem instanceof Node) {
            vo = Optional.of((Node) ifItem).map(nodeMapper).orElse(new CmpProperty());
        }
        return vo;
    }

    /**
     * EL→JSON：先取 trueCase 再取 falseCase，顺序即 children 的顺序
     * （生成 EL 时按下标还原，所以这里顺序不能乱）。
     */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();

        IfCondition ifCondition = (IfCondition) condition;
        // 获得 trueCase
        Executable trueCaseExecutableItem = ifCondition.getTrueCaseExecutableItem();
        addChildList(trueCaseExecutableItem, children);

        // 获得 falseCase
        Executable falseCaseExecutableItem = ifCondition.getFalseCaseExecutableItem();
        addChildList(falseCaseExecutableItem, children);

        return children;
    }

    /**
     * 单个分支的转换（Condition/Node/Chain 三分），空分支直接跳过
     * —— 这就是"没有 ELSE"时 children 只有一个元素的来源。
     */
    private void addChildList(Executable item, List<CmpProperty> children) {
        if (ObjectUtil.isNull(item)) {
            return;
        }
        // 可执行对象不为空，则去执行
        if (ObjectUtil.isNotNull(item)) {
            CmpProperty vo = null;
            if (item instanceof Condition) {
                // 这里解析器有: AndOrConditionParser、NotConditionParser
                vo = builderChildVO((Condition) item);
            } else if (item instanceof Node) {
                vo = Optional.of((Node) item).map(nodeMapper).orElse(new CmpProperty());
            }else if(item instanceof Chain){
                Chain chain = (Chain) item;
                vo = buildChildrenChain(chain);
            }
            children.add(vo);
        }
    }

    /** 第 1 步：IF 的模板（两个占位符） */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elIfMethod;
    }

    /**
     * 第 2 步：填条件位（第一个 {}）。
     * 条件位是普通布尔节点时直接用 id；是 AND/OR/NOT 子表达式时走
     * generateNodeComponent 递归生成整段表达式。
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
            // 布尔组件同样要带 tag（数据空间）与 data（path/op/value），否则运行时拿不到配置
            nodeComponentId = appendNodeIdTagData(condition, nodeComponentId);
        }
        // 使用与或非表达式: AND,OR,NOT
        else {
            nodeComponentId = generateNodeComponent(condition, nodeComponentId);
        }

        // IF({},{}) -> IF(AND(a,b),{})
        return StrUtil.replaceFirst(elExpress, "{}", nodeComponentId);
    }

    /**
     * 第 3 步：填分支（第二个 {}）。children 数量决定二元/三元：
     * 1 个 → 只有真分支；2 个 → 真假分支按逗号连接。
     */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isEmpty(jsonEl.getChildren()) || jsonEl.getChildren().isEmpty()) {
            return elExpress;
        }
        List<CmpProperty> children = jsonEl.getChildren();
        String caseNodeComponentId = "";
        // IF的二元表达式
        if (1 == children.size()) {
            CmpProperty trueCaseVO = children.get(0);
            // 获取 trueCase
            caseNodeComponentId = generateNodeComponent(trueCaseVO, caseNodeComponentId);
        }
        // IF的三元元表达式
        else if (2 == children.size()) {
            for (CmpProperty caseVO : children) {
                caseNodeComponentId = generateNodeComponent(caseVO, caseNodeComponentId);
                // 这里会多拼接一个逗号
                caseNodeComponentId = StrUtil.appendIfMissing(caseNodeComponentId, elSeparate);
            }
            // 去除多的逗号: c, d, -> c, d
            caseNodeComponentId = StringUtils.substringBeforeLast(caseNodeComponentId, elSeparate);
        }
        // IF(AND(a,b), {}) -> IF(AND(a,b), c, d)
        return StrUtil.format(elExpress, caseNodeComponentId);
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

    private CmpProperty andMapper(List<CmpProperty> voList) {
        return voList.stream()
                .filter(vo -> caseMatcher.test(vo.getType(), ChainConstant.AND))
                .findFirst()
                .orElse(null);
    }

    // 筛选 BREAK 类型  Matcher
    private final BiPredicate<String, String> caseMatcher = StringUtils::equals;

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.IF;
    }
}
