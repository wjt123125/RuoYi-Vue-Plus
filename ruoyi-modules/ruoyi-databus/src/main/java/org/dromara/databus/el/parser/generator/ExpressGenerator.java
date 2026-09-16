package org.dromara.databus.el.parser.generator;

import com.alibaba.qlexpress4.Express4Runner;
import com.alibaba.qlexpress4.QLResult;
import com.alibaba.qlexpress4.QLOptions;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.base.ExpressParser;
import org.dromara.databus.el.parser.selector.ParserSelector;
import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.common.ChainConstant;
import com.yomahub.liteflow.flow.FlowBus;
import com.yomahub.liteflow.flow.element.Condition;
import com.yomahub.liteflow.util.QlExpressUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 双向转换的对外门面 —— 画布 JSON（CmpProperty 树）与 LiteFlow EL 表达式互转的总入口。
 * <p>
 * 上层调用方：{@code ElGenerateController}（REST 接口 /databus/el/generate）、
 * {@code DatabusEditorController}、{@code DatabusChainServiceImpl}。
 * <p>
 * 两个方向的流水线：
 * <pre>
 * 【EL → JSON】generateJsonEL(ELInfo)
 *   1. QLExpress 执行 EL 字符串（上下文里预置了所有 chain/node 引用）
 *   2. 得到 LiteFlow 的 Condition 对象树（运行时结构）
 *   3. ParserSelector 找到根 Condition 对应的解析器
 *   4. builderVO + builderCondition + builderChildren 递归转成 CmpProperty 树
 *
 * 【JSON → EL】generateEL(CmpProperty) / verifyELExpression(CmpProperty)
 *   1. builderEL 走"模板法五步曲"递归拼出 EL 字符串
 *   2. verifyELExpression 额外用 LiteFlowChainELBuilder.validate 校验合法性
 * </pre>
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/19
 */
@Slf4j
@Component
public class ExpressGenerator {

    /**
     * EL → JSON：把 EL 表达式字符串解析成画布 JSON 树。
     * <p>
     * 关键点在上下文（context）的准备：QLExpress 执行 EL 时，表达式里的
     * 每个标识符（如 {@code THEN(a, b)} 中的 a、b）都是一次"变量求值"，
     * 必须预先把 chain 和 node 塞进上下文，表达式才能解析出真实的
     * Chain/Node 对象而不是报"变量不存在"。
     * <ul>
     *   <li>先放 chain 再放 node：node 的优先级更高，重名时 node 覆盖 chain；</li>
     *   <li>再放 CURR_CHAIN_ID：供表达式内 {@code chainId} 关键字引用当前链。</li>
     * </ul>
     * 解析失败时捕获异常并返回空 CmpProperty（不向上抛，画布侧表现为空树）。
     *
     * @param elInfo chainId + elStr
     * @return 画布 JSON 树根节点
     */
    public CmpProperty generateJsonEL(ELInfo elInfo) {
        CmpProperty cmpProperty = new CmpProperty();

        try {
            Map<String, Object> context = new HashMap<>();

            // 这里一定要先放chain，再放node，因为node优先于chain，所以当重名时，node会覆盖掉chain
            // 往上下文里放入所有的chain，是的el表达式可以直接引用到chain
            FlowBus.getChainMap().values().forEach(chain -> context.put(chain.getChainId(), chain));

            // 往上下文里放入所有的node，使得el表达式可以直接引用到nodeId
            FlowBus.getNodeMap().keySet().forEach(nodeId -> context.put(nodeId, FlowBus.getNode(nodeId)));

            // 放入当前主chain的ID
            assert elInfo != null;
            context.put(ChainConstant.CURR_CHAIN_ID, elInfo.getChainId());

            // promotionChain: THEN(fullCutCmp, fullDiscountCmp, rushBuyCmp);
            String elStr = elInfo.getElStr();
            log.info("----- {}", elStr);
            Express4Runner runner = QlExpressUtils.getELExpressRunner();
            // 执行 EL：LiteFlow 底层用 QLExpress 求值，THEN/WHEN 等关键字被注册为
            // 操作符，最终返回的是该表达式对应的 Condition 运行时对象树
            QLResult expressResult = runner.execute(elStr, context, QLOptions.builder().cache(true).build());
            Condition condition = (Condition) expressResult.getResult();

            // 设置最 外层 内层, 其实每一层都是这样的
            // 1.id, condition是没有组件编码的,只有Node的时候才有
            // 2.type格式: THEN,SWITCH,IF,WHEN,FOR,WHILE,CATCH
            // 3.properties: id, tag 只有condition才有的属性, Node没有
            // 4.condition, 根据类型来区分, 比如:THEN、WHEN就没有条件
            // 5.children

            cmpProperty = builderJsonEL(condition);

        } catch (Exception ex) {
            log.error("-----", ex);
        }

        return cmpProperty;
    }

    /**
     * EL→JSON 的递归三件套：对任意一个 Condition，
     * 用它的解析器依次填 VO 外壳、条件位、子分支（children 内部继续递归）。
     */
    private CmpProperty builderJsonEL(Condition condition) {
        ExpressParser parser = ParserSelector.getParser(condition);
        // id, type, property
        CmpProperty cmpProperty = parser.builderVO(condition);
        // conditionList
        cmpProperty.setCondition(parser.builderCondition(condition));
        // childList
        cmpProperty.setChildren(parser.builderChildren(condition));
        return cmpProperty;
    }


    /**
     * JSON → EL：画布 JSON 树转 EL 字符串（不校验）。
     */
    public ELInfo generateEL(CmpProperty jsonEl) {
        ELInfo vo = new ELInfo();
        String elStr = builderEL(jsonEl);

        vo.setElStr(elStr);
        return vo;
    }

    /**
     * JSON → EL + 合法性校验：生成 EL 后交给 LiteFlow 官方
     * {@link LiteFlowChainELBuilder#validate} 验证能否被解析，
     * 前端保存编排前用它做"语法体检"。
     */
    public boolean verifyELExpression(CmpProperty jsonEl) {
        String elStr = builderEL(jsonEl);
        if (null == elStr) {
            return false;
        }
        log.info("生成EL表达式成功, elStr: {}", elStr);
        return LiteFlowChainELBuilder.validate(elStr);
    }

    /**
     * JSON→EL 的私有主干，与 AbstractExpressParser#abstractGenerateEL 五步曲一致
     * （多补了一步分号收尾）。
     */
    private String builderEL(CmpProperty jsonEl) {
        ExpressParser parser = ParserSelector.getParser(jsonEl.getType().toLowerCase());

        // 1.生成外部函数表达式 THEN({})
        String elExpress = parser.generateELMethod(jsonEl);
        // 2.填充EL条件, THEN没有条件, THEN(a, b, c)
        elExpress = parser.generateCondition(jsonEl, elExpress);
        // 3.填充EL组件 THEN(a, b, c)
        elExpress = parser.generateCmp(jsonEl, elExpress);
        // 4.拼接属性 THEN(a, b, c).id("dog")
        elExpress = parser.generateIdAndTag(jsonEl, elExpress);
        // 5.补充分号 THEN(a, b, c).id("dog");
        elExpress = parser.generateELEnd(jsonEl, elExpress);

        return elExpress;
    }


}
