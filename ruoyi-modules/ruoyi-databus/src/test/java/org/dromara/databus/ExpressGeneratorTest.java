package org.dromara.databus;

import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import com.yomahub.liteflow.common.ChainConstant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExpressGenerator 往返测试：画布 JSON ↔ EL 表达式双向转换一致性。
 *
 * @author databus
 */
@Tag("dev")
@SpringBootTest(classes = DatabusSmokeTestApplication.class)
class ExpressGeneratorTest {

    @Autowired
    private ExpressGenerator expressGenerator;

    /**
     * 构造 THEN(hello, delay) 对应的画布 JSON。
     * 节点 type 必须为 NodeComponent（LiteFlow 普通节点的映射类简称），
     * generateNodeComponent 据此判定为普通节点并拼接 id。
     */
    private CmpProperty buildThenHelloDelay() {
        CmpProperty hello = CmpProperty.builder()
                .id("hello")
                .type("NodeComponent")
                .build();
        CmpProperty delay = CmpProperty.builder()
                .id("delay")
                .type("NodeComponent")
                .build();
        return CmpProperty.builder()
                .type("THEN")
                .children(Arrays.asList(hello, delay))
                .build();
    }

    @Test
    void generateEL_then_shouldProduceExpectedExpression() {
        CmpProperty jsonEl = buildThenHelloDelay();
        ELInfo elInfo = expressGenerator.generateEL(jsonEl);

        assertNotNull(elInfo);
        // THEN(hello,delay);
        assertEquals("THEN(hello,delay);", elInfo.getElStr());
    }

    @Test
    void verifyELExpression_then_shouldReturnTrue() {
        CmpProperty jsonEl = buildThenHelloDelay();
        assertTrue(expressGenerator.verifyELExpression(jsonEl));
    }

    /**
     * 构造 ITERATOR(iteratorCmp).DO(THEN(hello, delay)) 对应的画布 JSON。
     * 迭代条件节点 type 必须为 NodeIteratorComponent（LiteFlow 迭代节点的映射类简称）。
     */
    private CmpProperty buildIteratorDoThen() {
        CmpProperty iteratorNode = CmpProperty.builder()
            .id("iteratorCmp")
            .type("NodeIteratorComponent")
            .build();
        CmpProperty doBody = buildThenHelloDelay();
        return CmpProperty.builder()
            .type("ITERATOR")
            .condition(iteratorNode)
            .children(new ArrayList<>(Arrays.asList(doBody)))
            .build();
    }

    /**
     * 构造 ITERATOR(iteratorCmp).DO(THEN(hello, delay)).BREAK(breakCmp) 对应的画布 JSON。
     * BREAK 子节点为包装节点（type=BREAK），其内部挂一个 NodeBooleanComponent 节点。
     */
    private CmpProperty buildIteratorWithBreak() {
        CmpProperty iterator = buildIteratorDoThen();
        CmpProperty breakNode = CmpProperty.builder()
            .id("breakCmp")
            .type("NodeBooleanComponent")
            .build();
        CmpProperty breakWrapper = CmpProperty.builder()
            .type(ChainConstant.BREAK)
            .children(new ArrayList<>(Arrays.asList(breakNode)))
            .build();
        List<CmpProperty> children = new ArrayList<>(iterator.getChildren());
        children.add(breakWrapper);
        iterator.setChildren(children);
        return iterator;
    }

    @Test
    void generateEL_iterator_shouldProduceExpectedExpression() {
        ELInfo elInfo = expressGenerator.generateEL(buildIteratorDoThen());

        assertNotNull(elInfo);
        assertEquals("ITERATOR(iteratorCmp).DO(THEN(hello,delay));", elInfo.getElStr());
    }

    @Test
    void verifyELExpression_iterator_shouldReturnTrue() {
        assertTrue(expressGenerator.verifyELExpression(buildIteratorDoThen()));
    }

    @Test
    void roundTrip_iterator_elToJsonToEl_shouldBeConsistent() {
        // EL -> JSON
        ELInfo input = new ELInfo();
        input.setChainId("iteratorRoundTripChain");
        input.setElStr("ITERATOR(iteratorCmp).DO(THEN(hello,delay));");
        CmpProperty jsonEl = expressGenerator.generateJsonEL(input);

        assertNotNull(jsonEl);
        assertEquals("ITERATOR", jsonEl.getType());
        assertNotNull(jsonEl.getCondition());
        assertEquals("iteratorCmp", jsonEl.getCondition().getId());

        // JSON -> EL
        ELInfo output = expressGenerator.generateEL(jsonEl);
        assertNotNull(output);
        assertEquals("ITERATOR(iteratorCmp).DO(THEN(hello,delay));", output.getElStr());
    }

    @Test
    void generateEL_iteratorWithBreak_shouldProduceExpectedExpression() {
        ELInfo elInfo = expressGenerator.generateEL(buildIteratorWithBreak());

        assertNotNull(elInfo);
        assertEquals("ITERATOR(iteratorCmp).DO(THEN(hello,delay)).BREAK(breakCmp);",
            elInfo.getElStr());
    }

    @Test
    void roundTrip_elToJsonToEl_shouldBeConsistent() {
        // EL -> JSON
        ELInfo input = new ELInfo();
        input.setChainId("roundTripChain");
        input.setElStr("THEN(hello,delay);");
        CmpProperty jsonEl = expressGenerator.generateJsonEL(input);

        assertNotNull(jsonEl);
        assertEquals("THEN", jsonEl.getType());
        assertNotNull(jsonEl.getChildren());
        assertEquals(2, jsonEl.getChildren().size());

        // JSON -> EL
        ELInfo output = expressGenerator.generateEL(jsonEl);
        assertNotNull(output);
        assertEquals("THEN(hello,delay);", output.getElStr());
    }
}
