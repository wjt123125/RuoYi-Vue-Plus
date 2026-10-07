package org.dromara.databus.el.parser.el;

import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.base.AbstractExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CHAIN（子流程引用）解析器：{@code subChain.tag("x")}。
 * <p>
 * CHAIN 叶子与其他解析器有本质区别——它引用的是<b>另一条链</b>，不是一种
 * Condition 语法（{@link ConditionTypeEnum} 没有 chain 项），所以：
 * <ul>
 *   <li>{@code parserType()} 返回 null：EL→JSON 方向 Chain 引用由父表达式
 *       的 builderChildList 分派给 {@code buildChildrenChain} 处理，
 *       不走 Condition 路由，本解析器不参与；</li>
 *   <li>覆盖 {@code parserKey()} 返回 "chain"：JSON→EL 方向当画布 JSON 的
 *       根节点是 CHAIN（整条链只有一个子流程引用节点）时，
 *       {@code builderEL} 按 type "chain" 路由到本解析器。</li>
 * </ul>
 * 场景说明：CHAIN 作为<b>父表达式的子项</b>时不需要本解析器——
 * generateNodeComponent 对 id 非空的子项统一走 {@code appendNodeIdTagData}
 * （拼 id.tag.data，不查 type），天然覆盖 {@code THEN(a, subChain)} 里
 * 的 CHAIN 叶子。本解析器只兜底根节点场景（否则 getParser("chain") 抛异常）。
 * <p>
 * 生成模板即占位符 {@code {}}：五步曲里没有外层函数，第 3 步直接把
 * {@code id.tag("x")}（无则只拼 id）填进占位符，产出
 * {@code subChain.tag("x");}。
 *
 * @author databus
 * @since 2026/10/7
 */
@Component
public class ChainParser extends AbstractExpressParser {

    /**
     * 注册 key：chain（与画布 JSON 的 CmpProperty.type 小写一致）。
     * CHAIN 不是 Condition 类型，靠覆盖本方法注册进 PARSER_MAP。
     */
    @Override
    public String parserKey() {
        return ExpressParserEnum.CHAIN.getType();
    }

    /** CHAIN 引用不对应任何 Condition 类型，返回 null（不参与 Condition 路由） */
    @Override
    public ConditionTypeEnum parserType() {
        return null;
    }

    /** EL→JSON 反向映射不会被调用到（Chain 不走 Condition 路由），接口完备性实现 */
    @Override
    public ExpressParserEnum getExpressType(Condition condition) {
        return ExpressParserEnum.CHAIN;
    }

    /** CHAIN 叶子没有条件位 */
    @Override
    public CmpProperty builderCondition(Condition condition) {
        return null;
    }

    /** CHAIN 叶子没有子分支 */
    @Override
    public List<CmpProperty> builderChildren(Condition condition) {
        return null;
    }

    /** 第 1 步：CHAIN 叶子没有外层函数，模板就是占位符本身 */
    @Override
    public String generateELMethod(CmpProperty jsonEl) {
        return elPlaceholder;
    }

    /** 第 2 步：无条件位，模板原样返回 */
    @Override
    public String generateCondition(CmpProperty jsonEl, String elExpress) {
        return elExpress;
    }

    /**
     * 第 3 步：填充引用本体——走公共的 id.tag("x").data("y") 拼接，
     * 产出 {@code subChain.tag("x")}，与其他节点叶子的拼法完全一致。
     */
    @Override
    public String generateCmp(CmpProperty jsonEl, String elExpress) {
        return StrUtil.format(elExpress, appendNodeIdTagData(jsonEl, ""));
    }

    /** 第 4 步：tag/data 已在第 3 步随节点拼接，原样返回防重复 */
    @Override
    public String generateIdAndTag(CmpProperty jsonEl, String elExpress) {
        return elExpress;
    }
}
