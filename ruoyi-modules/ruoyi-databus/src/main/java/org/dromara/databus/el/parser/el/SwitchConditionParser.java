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
import com.yomahub.liteflow.flow.element.condition.SwitchCondition;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SWITCH（选择编排）解析器：{@code SWITCH(x).to(a, b, c)}。
 * <p>
 * 结构要点：
 * <ul>
 *   <li><b>条件位 condition</b> = 选择器节点 x（type=NodeSwitchComponent），
 *       运行时其返回值决定走哪个分支；</li>
 *   <li><b>children</b> = to 列表（候选分支，可以是节点/子表达式/子链）。</li>
 * </ul>
 * JSON→EL：模板 {@code SWITCH({}).to({})} 的两个 {} 分别填选择器与分支列表。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Component
public class SwitchConditionParser extends AbstractExpressParser {

    private final String type = ConditionTypeEnum.TYPE_SWITCH.getType();

    /**
     * 注册 key：LiteFlow 的 switch 类型。
     * @see ChainConstant
     * @see ConditionTypeEnum
     * */
    @Override
    public ConditionTypeEnum parserType() {
        return ConditionTypeEnum.TYPE_SWITCH;
    }

    /** EL→JSON：取 SwitchCondition 的 switchNode（选择器节点）作为条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        SwitchCondition switchCondition = (SwitchCondition) condition;
        Node switchNode = switchCondition.getSwitchNode();

        // Node 转 CmpPropertyVO
        return Optional.of(switchNode).map(nodeMapper).orElse(new CmpProperty());
    }

    /** EL→JSON：to 的候选分支列表（三分遍历 Condition/Node/Chain） */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        List<CmpProperty> children = new ArrayList<>();
        SwitchCondition switchCondition = (SwitchCondition) condition;
        List<Executable> executableList = switchCondition.getTargetList();
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

    /**
     * EL→JSON：在公共外壳基础上，把各分支表达式的 tag 收集成 properties.outletLabels，
     * 即画布上的 case 名列表（与 children 下标一一对应；无 tag 的分支为 null）。
     * JSON→EL 时再由 outletLabels 反向挂回分支表达式，保证往返一致。
     */
    @Override
    public CmpProperty builderVO(Condition condition) {
        CmpProperty vo = super.builderVO(condition);
        SwitchCondition switchCondition = (SwitchCondition) condition;
        List<String> outletLabels = switchCondition.getTargetList().stream()
            .map(Executable::getTag)
            .toList();
        if (outletLabels.stream().anyMatch(StringUtils::isNotBlank)) {
            Properties properties = Optional.ofNullable(vo.getProperties()).orElseGet(Properties::new);
            properties.setOutletLabels(outletLabels);
            vo.setProperties(properties);
        }
        return vo;
    }

    /** 第 1 步：SWITCH 模板（两个占位符：选择器 + to 列表） */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elSwitchMethod;
    }

    /**
     * 第 2 步：填选择器（第一个 {}）。
     * 选择器是 NodeSwitchComponent 类型的普通节点，必须带 tag（实例唯一/数据空间）
     * 与 data（source/cases 参数），故走 appendNodeIdTagData 而非裸 id。
     */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        if (Objects.isNull(jsonEl.getCondition())) {
            return elExpress;
        }
        CmpProperty condition = jsonEl.getCondition();
        String nodeComponentId = appendNodeIdTagData(condition, "");
        // SWITCH({}).to({}) -> SWITCH(switchRoute.tag("switchRoute1").data("{...}")).to({})
        return StrUtil.replaceFirst(elExpress, "{}", nodeComponentId);
    }

    /**
     * 第 3 步：填 to 分支列表（第二个 {}），逐项生成并按 outletLabels[i]
     * 给每个分支挂 {@code .tag("case名")}，供 switchRoute 返回 {@code ":case名"} 命中。
     * <ul>
     *     <li>单节点分支：先包一层 THEN 再挂 tag，避免 tag 落到节点自身（那是它的数据空间名）；</li>
     *     <li>子表达式分支：tag 直接挂表达式末尾；若该表达式已自带同名 tag
     *     （EL→JSON→EL 往返：tag 已落在子表达式 properties 上），不重复挂。</li>
     * </ul>
     */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        if (CollectionUtil.isEmpty(jsonEl.getChildren())) {
            return elExpress;
        }
        List<String> outletLabels = Optional.ofNullable(jsonEl.getProperties())
            .map(Properties::getOutletLabels)
            .orElse(null);
        String nodeComponentId = "";
        List<CmpProperty> children = jsonEl.getChildren();
        for (int i = 0; i < children.size(); i++) {
            String fragment = generateNodeComponent(children.get(i), "");
            fragment = appendOutletTag(children.get(i), fragment, outletLabels, i);
            nodeComponentId = StringUtils.appendIfMissing(nodeComponentId, fragment);
            // 这里会多拼接一个逗号
            nodeComponentId = StrUtil.appendIfMissing(nodeComponentId, elSeparate);
        }
        // 去除多的逗号
        nodeComponentId = StringUtils.substringBeforeLast(nodeComponentId, elSeparate);
        // 填充EL组件, SWITCH(a).to({}) -> SWITCH(a).to(THEN(b).tag("x"), c)
        return StrUtil.format(elExpress, nodeComponentId);
    }

    /**
     * 给单个 SWITCH 分支片段挂 outlet tag（case 名），规则见 {@link #generateCmp}。
     */
    private String appendOutletTag(CmpProperty child, String fragment, List<String> outletLabels, int index) {
        if (outletLabels == null || index >= outletLabels.size()) {
            return fragment;
        }
        String label = outletLabels.get(index);
        if (StringUtils.isBlank(label)) {
            return fragment;
        }
        String tagFragment = StrUtil.format(elNodeTag, label);
        if (child.getId() != null) {
            // 单节点：THEN(a.tag("a1")) 外包一层，case tag 挂在 THEN 表达式上
            return StringUtils.appendIfMissing(StrUtil.format(elThenMethod, fragment), tagFragment);
        }
        // 子表达式已自带 tag（EL 往返场景）时不重复挂
        String existingTag = child.getProperties() == null ? null : child.getProperties().getTag();
        if (StringUtils.isBlank(existingTag)) {
            return StringUtils.appendIfMissing(fragment, tagFragment);
        }
        return fragment;
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

    private boolean nonLiteFlowConditionId(String id) {
        // LF默认id: condition-switch
        return !StringUtils.equals(defaultConditionId(), id);
    }

    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.SWITCH;
    }
}
