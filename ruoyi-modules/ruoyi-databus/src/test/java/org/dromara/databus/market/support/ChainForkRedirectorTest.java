package org.dromara.databus.market.support;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.parser.el.ThenConditionParser;
import org.dromara.databus.el.parser.factory.ExpressParserFactory;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChainForkRedirector 引用重定向单元测试（decisions §十四-3）。
 * <p>
 * plan §4 单测锚点：① 嵌套 A→B→C 三层重定向后，副本树经真实
 * {@link ExpressGenerator#generateEL} 生成的 CHAIN 引用逐个等于新 code（EL 语义等价，
 * generateEL 无 QLExpress/FlowBus 依赖可直构）；② 兜底路径 properties.id 也能替换；
 * ③ 源树不动、普通节点同名不受误伤；④ canvasData 的 CHAIN 节点 cmpId 改写、
 * 双击下钻指向副本。纯 JUnit5 零 mock。
 *
 * @author databus
 */
@Tag("dev")
class ChainForkRedirectorTest {

    private final ExpressGenerator expressGenerator = new ExpressGenerator();

    private static final Map<String, String> MAPPING = Map.of(
        "chainB", "u1__chainB",
        "chainC", "u1__chainC");

    /**
     * PARSER_MAP 靠 Spring @Autowired 注入解析器，纯 JUnit 环境为空——手工注册
     * 测试树用到的 then 解析器（THEN 子项里的 CHAIN/普通叶子走公共拼接、不按
     * type 路由，见 ChainParser 类注释，注册一个 then 即够）。ConcurrentHashMap 幂等。
     */
    @BeforeAll
    static void registerParsers() {
        new ExpressParserFactory().register(new ThenConditionParser());
    }

    /**
     * 普通组件叶子。
     */
    private static CmpProperty node(String id) {
        return CmpProperty.builder().id(id).type("NodeComponent").build();
    }

    /**
     * 子链引用叶子（顶层 id 形态，collectChainRefs 的主路径）。
     */
    private static CmpProperty chainRef(String id) {
        return CmpProperty.builder().id(id).type("CHAIN").build();
    }

    /**
     * THEN 顺序容器。
     */
    private static CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }

    /**
     * 提取 EL 里的标识符 token 列表：避免「u1__chainB」包含「chainB」子串导致的假失败
     * （\w+ 整词匹配，u1__chainB 是单个 token，不会等于 chainB）。
     */
    private static List<String> tokens(String el) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\w+").matcher(el);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    /**
     * 嵌套 A→B→C 三层闭包：每条链的副本树重定向后，generateEL 输出的
     * CHAIN 引用逐个等于新 code、旧 code 不再以独立 token 出现、普通节点不动
     * （plan §4「EL 语义等价」锚点；副本 EL 由重定向后的树重生成，同 copy() 口径）。
     */
    @Test
    void redirectChainRefs_nestedAbc_elReferencesRedirected() {
        CmpProperty chainA = then(node("a"), chainRef("chainB"));
        CmpProperty chainB = then(node("b"), chainRef("chainC"));
        CmpProperty chainC = then(node("c"));

        CmpProperty copyA = ChainForkRedirector.redirectChainRefs(chainA, MAPPING);
        CmpProperty copyB = ChainForkRedirector.redirectChainRefs(chainB, MAPPING);
        CmpProperty copyC = ChainForkRedirector.redirectChainRefs(chainC, MAPPING);

        String elA = expressGenerator.generateEL(copyA).getElStr();
        String elB = expressGenerator.generateEL(copyB).getElStr();
        String elC = expressGenerator.generateEL(copyC).getElStr();

        // A 引用 B：新 code 出现、旧 code 不再是独立 token、普通节点 a 保留
        assertTrue(tokens(elA).contains("u1__chainB"), "A 的 CHAIN 引用应为新 code: " + elA);
        assertTrue(!tokens(elA).contains("chainB"), "A 的旧引用应消失: " + elA);
        assertTrue(tokens(elA).contains("a"), "普通节点不应被误伤: " + elA);

        // B 引用 C：同口径
        assertTrue(tokens(elB).contains("u1__chainC"), "B 的 CHAIN 引用应为新 code: " + elB);
        assertTrue(!tokens(elB).contains("chainC"), "B 的旧引用应消失: " + elB);
        assertTrue(tokens(elB).contains("b"), "普通节点不应被误伤: " + elB);

        // C 无引用：EL 与源树生成结果一致（语义等价）
        assertEquals(expressGenerator.generateEL(chainC).getElStr(), elC);
    }

    /**
     * 兜底路径（decisions §十四-3「历史数据兜底 properties.id」）：
     * CHAIN 叶子顶层 id 为空、properties.id 携带引用 code，也能替换。
     */
    @Test
    void redirectChainRefs_legacyPropertiesIdFallback() {
        CmpProperty legacy = CmpProperty.builder()
            .type("CHAIN")
            .properties(Properties.builder().id("chainB").tag("t").data("{}").build())
            .build();

        CmpProperty copy = ChainForkRedirector.redirectChainRefs(legacy, MAPPING);

        assertNull(copy.getId(), "顶层 id 本为空，重定向不应凭空补值");
        assertEquals("u1__chainB", copy.getProperties().getId(), "兜底 properties.id 应替换为新 code");
        assertEquals("t", copy.getProperties().getTag(), "非引用字段不应被动");
        assertEquals("{}", copy.getProperties().getData(), "非引用字段不应被动");
    }

    /**
     * CHAIN 叶子顶层 id 与 properties.id 同时携带同一引用：两处各自独立命中替换，其余属性保留。
     */
    @Test
    void redirectChainRefs_bothIdAndPropertiesIdRemapped() {
        CmpProperty ref = CmpProperty.builder()
            .id("chainB").type("chain")
            .properties(Properties.builder().id("chainB").title("子链B标题").build())
            .build();

        CmpProperty copy = ChainForkRedirector.redirectChainRefs(ref, MAPPING);

        assertEquals("u1__chainB", copy.getId());
        assertEquals("u1__chainB", copy.getProperties().getId());
        assertEquals("子链B标题", copy.getProperties().getTitle());
    }

    /**
     * 深拷贝不动源：重定向后源树各层字段保持原值，返回的是无共享引用的独立树。
     */
    @Test
    void redirectChainRefs_sourceTreeUntouched() {
        CmpProperty source = then(node("a"), chainRef("chainB"));

        CmpProperty copy = ChainForkRedirector.redirectChainRefs(source, MAPPING);

        assertNotSame(source, copy);
        assertNotSame(source.getChildren(), copy.getChildren());
        assertEquals("chainB", source.getChildren().get(1).getId(), "源树 CHAIN 引用不应被改写");
        assertEquals("u1__chainB", copy.getChildren().get(1).getId());
    }

    /**
     * 防误伤：普通节点 id 与链 code 同名（LiteFlow node/chain 同池可重名），
     * 仅 type=CHAIN（大小写不敏感）的叶子参与重定向，普通节点一律不动。
     */
    @Test
    void redirectChainRefs_sameCodeOnNormalNodeNotTouched() {
        CmpProperty tree = then(chainRef("chainB"), node("chainB"));

        CmpProperty copy = ChainForkRedirector.redirectChainRefs(tree, MAPPING);

        assertEquals("u1__chainB", copy.getChildren().get(0).getId());
        assertEquals("chainB", copy.getChildren().get(1).getId(), "普通节点同名 id 不应被误伤");
    }

    /**
     * 空映射/空树短路：无需改写时原引用直接返回，null 进 null 出。
     */
    @Test
    void redirectChainRefs_emptyMappingOrNull_returnsOriginal() {
        CmpProperty tree = then(chainRef("chainB"));

        assertSame(tree, ChainForkRedirector.redirectChainRefs(tree, Map.of()));
        assertNull(ChainForkRedirector.redirectChainRefs(null, MAPPING));
    }

    /**
     * 画布改写：data.defType=CHAIN 节点的 cmpId 命中替换（大小写不敏感判定 defType），
     * label 展示字段与 edges 保留，前端双击下钻按 data.cmpId 落到副本链。
     */
    @Test
    void redirectCanvasData_chainNodesRedirected() {
        String canvas = """
            {"nodes":[
              {"id":"n1","type":"cmp","data":{"defType":"CHAIN","cmpId":"chainB","label":"子链B"}},
              {"id":"n2","type":"cmp","data":{"defType":"COMPONENT","cmpId":"chainB","label":"同名组件"}},
              {"id":"n3","type":"cmp","data":{"defType":"chain","cmpId":"chainC","label":"小写defType"}},
              {"id":"n4","type":"cmp","data":{"defType":"CHAIN","cmpId":"chainX","label":"未命中"}}
            ],"edges":[{"id":"e1","source":"n1","target":"n4"}]}""";

        String redirected = ChainForkRedirector.redirectCanvasData(canvas, MAPPING);

        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode nodes = mapper.readTree(redirected).path("nodes");

        assertEquals("u1__chainB", nodes.path(0).path("data").path("cmpId").asText(null), "CHAIN 节点引用应改写");
        assertEquals("子链B", nodes.path(0).path("data").path("label").asText(null), "展示字段不应被动");
        assertEquals("chainB", nodes.path(1).path("data").path("cmpId").asText(null), "非 CHAIN 节点即使同名也不应被误伤");
        assertEquals("u1__chainC", nodes.path(2).path("data").path("cmpId").asText(null), "defType 大小写不敏感");
        assertEquals("chainX", nodes.path(3).path("data").path("cmpId").asText(null), "未命中映射不应被动");

        JsonNode edge = mapper.readTree(redirected).path("edges").path(0);
        assertEquals("e1", edge.path("id").asText(null));
        assertEquals("n1", edge.path("source").asText(null));
        assertEquals("n4", edge.path("target").asText(null));
    }

    /**
     * 无命中（仅非 CHAIN 节点或 code 不在映射）与空入参：原字符串原样返回，避免无谓重序列化。
     */
    @Test
    void redirectCanvasData_noHitOrBlank_returnsOriginal() {
        String noHit = "{\"nodes\":[{\"id\":\"n1\",\"data\":{\"defType\":\"COMPONENT\",\"cmpId\":\"chainX\"}}]}";
        assertSame(noHit, ChainForkRedirector.redirectCanvasData(noHit, MAPPING));

        String notObject = "[1,2]";
        assertSame(notObject, ChainForkRedirector.redirectCanvasData(notObject, MAPPING));

        assertNull(ChainForkRedirector.redirectCanvasData(null, MAPPING));
        assertSame("", ChainForkRedirector.redirectCanvasData("", MAPPING));
        String plain = "{\"nodes\":[]}";
        assertSame(plain, ChainForkRedirector.redirectCanvasData(plain, Map.of()));
    }

    /**
     * 非法画布 JSON：抛 ServiceException（对齐 DatabusMarketServiceImpl#readManifest 口径）。
     */
    @Test
    void redirectCanvasData_invalidJson_throwsServiceException() {
        assertThrows(ServiceException.class,
            () -> ChainForkRedirector.redirectCanvasData("not a json", MAPPING));
    }

    /**
     * 混合树的组件叶子收集（decisions §十四-6 / §十四-7 共用口径）：只收业务组件叶子，
     * CHAIN 引用（ChainConstant.CHAIN 原值小写 "chain"，EL_KEYWORDS 构建时 toUpperCase
     * 归一——大小写不归一则 CHAIN 叶子会被误判成组件收进依赖清单，本用例即回归）与
     * THEN 等结构节点都不收；重复出现按首次顺序去重。
     */
    @Test
    void collectComponentCodes_mixedTree_onlyBusinessNodes() {
        CmpProperty tree = then(
            chainRef("chainB"),
            node("compX"),
            then(node("compX"), node("compY")));

        List<String> codes = ChainForkRedirector.collectComponentCodes(tree);

        assertEquals(List.of("compX", "compY"), codes, "只应收业务组件叶子，去重保序");
    }

    /**
     * 兜底路径：业务组件叶子顶层 id 为空、properties.id 携带编码时也收集（与
     * collectChainRefs 的历史数据兜底口径对称）。
     */
    @Test
    void collectComponentCodes_legacyPropertiesIdFallback() {
        CmpProperty legacy = CmpProperty.builder()
            .type("NodeComponent")
            .properties(Properties.builder().id("compLegacy").build())
            .build();

        assertEquals(List.of("compLegacy"), ChainForkRedirector.collectComponentCodes(then(legacy)));
    }

    /**
     * 空入参与纯结构树：null 进空列表出；只有 THEN/CHAIN 结构节点时无组件叶子。
     */
    @Test
    void collectComponentCodes_nullOrStructureOnly_emptyList() {
        assertTrue(ChainForkRedirector.collectComponentCodes(null).isEmpty());
        assertTrue(ChainForkRedirector.collectComponentCodes(then(chainRef("chainB"))).isEmpty());
    }
}
