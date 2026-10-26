package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.el.ThenConditionParser;
import org.dromara.databus.el.parser.factory.ExpressParserFactory;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.MarketInstallService;
import org.dromara.databus.market.support.MarketClosureLoader;
import org.dromara.databus.service.IDatabusChainService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * install 链路全 fork 分支单元测试（decisions §十四-1/2/3/4）。
 * <p>
 * plan §5 锚点：① 嵌套 A→B→C 闭包 post-order 逐条复制独立成行（顶层平铺）、三字段重定向、
 * 台账仅记主链；② 撞名同源显式复用（REUSED，级联清单「使用你现有副本」）；③ 异源撞名
 * 后缀唯一化兜底；④ 闭包市场校验拒装先于任何写入；⑤ install 事务注解与复制中途异常
 * 传播（异常出方法边界即回滚，纯 Mockito 无事务代理，以注解＋异常传播＋零台账断言覆盖）。
 * <p>
 * 纯 JUnit5＋Mockito 直构安装服务：依赖清单收集 mock chainService、EL 生成直构
 * {@link ExpressGenerator}（无 QLExpress/FlowBus 依赖）。
 *
 * @author databus
 */
@Tag("dev")
class DatabusMarketInstallForkTest {

    private static final Long USER_ID = 1L;
    private static final Long ITEM_ID = 11L;
    private static final Long DIRECTORY_ID = 5L;

    private DatabusMarketItemMapper marketItemMapper;
    private DatabusInstallMapper installMapper;
    private DatabusChainMapper chainMapper;
    private IDatabusChainService chainService;
    private MarketInstallService service;

    /** insert 发号器（替代 MyBatis-Plus 主键回填）。 */
    private final AtomicLong idSeq = new AtomicLong(100);
    /** 已插入的链路副本（按 post-order 顺序收集）。 */
    private final List<DatabusChain> insertedChains = new ArrayList<>();
    /** 已插入的台账行（全 fork 仅主链一条）。 */
    private final List<DatabusInstall> insertedInstalls = new ArrayList<>();
    /** 编码存在性计数表（existsChainCode 的 eq 精确码查询用）。 */
    private final Map<String, Long> chainCodeCounts = new HashMap<>();

    private DatabusChain chainA;
    private DatabusChain chainB;
    private DatabusChain chainC;

    /**
     * 纯 Mockito 环境无 Spring/MyBatis 装配：注册三个实体元数据（LambdaQueryWrapper 的
     * lambda 列解析依赖 TableInfoHelper 全局缓存，幂等）；PARSER_MAP 靠 Spring 注入、纯
     * JUnit 为空——手工注册 then 解析器（generateEL 拼接 THEN 子项不按 type 路由，注册即够）。
     */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DatabusChain.class);
        TableInfoHelper.initTableInfo(assistant, DatabusInstall.class);
        TableInfoHelper.initTableInfo(assistant, DatabusMarketItem.class);
        new ExpressParserFactory().register(new ThenConditionParser());
    }

    @BeforeEach
    void setUp() {
        marketItemMapper = mock(DatabusMarketItemMapper.class);
        installMapper = mock(DatabusInstallMapper.class);
        chainMapper = mock(DatabusChainMapper.class);
        chainService = mock(IDatabusChainService.class);
        DatabusComponentMapper componentMapper = mock(DatabusComponentMapper.class);
        service = new MarketInstallService(
            marketItemMapper,
            installMapper,
            chainMapper,
            componentMapper,
            mock(DatabusConnectorDefMapper.class),
            chainService,
            new ExpressGenerator(),
            // 依赖清单装配用真实实现：它调的 collectChainClosure / selectList 已在下面打桩
            new MarketClosureLoader(chainService, marketItemMapper, componentMapper));

        chainA = chain(1L, "chainA", "链A",
            then(node("a"), chainRef("chainB")), canvas("a", "chainB"));
        chainB = chain(2L, "chainB", "链B",
            then(node("b"), chainRef("chainC")), canvas("b", "chainC"));
        chainC = chain(3L, "chainC", "链C", then(node("c")), canvas("c", null));

        // 默认场景＝安装链A 物料（sourceId=1，ONLINE）
        when(marketItemMapper.selectById(ITEM_ID)).thenReturn(item(ITEM_ID, 1L));
        // 闭包市场校验的 in 查：A/B/C 三行全 ONLINE（decisions §十四-4）
        when(marketItemMapper.selectList(any())).thenReturn(List.of(
            item(ITEM_ID, 1L), item(12L, 2L), item(13L, 3L)));
        when(chainMapper.selectById(1L)).thenReturn(chainA);
        when(chainMapper.selectById(2L)).thenReturn(chainB);
        // collectChainClosure 契约：post-order（被引用者先于引用者），主链居末
        when(chainService.collectChainClosure("chainA"))
            .thenReturn(closure(chainC, chainB, chainA));
        when(chainService.collectChainClosure("chainB"))
            .thenReturn(closure(chainC, chainB));

        // selectCount 三来源统一 answer：existsChainCode 的 eq 精确码查计数表；
        // refreshCounts 的 refCount like 模式值与 countForkPublished 的 in id 值查不到 → 0
        when(chainMapper.selectCount(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof AbstractWrapper<?, ?, ?> wrapper) {
                wrapper.getSqlSegment();
                for (Object value : wrapper.getParamNameValuePairs().values()) {
                    if (value instanceof String code) {
                        Long count = chainCodeCounts.get(code);
                        if (count != null) {
                            return count;
                        }
                    }
                }
            }
            return 0L;
        });
        // installMapper.selectCount 两来源：查重（eq ownerId＋marketItemId）→ 未装 0；
        // refreshCounts（仅 eq marketItemId）→ 装后 1
        when(installMapper.selectCount(any())).thenAnswer(inv -> {
            Object arg = inv.getArgument(0);
            if (arg instanceof AbstractWrapper<?, ?, ?> wrapper) {
                wrapper.getSqlSegment();
                return wrapper.getParamNameValuePairs().containsValue(USER_ID) ? 0L : 1L;
            }
            return 0L;
        });
        // countForkPublished 的 localIds 查询：空列表短路（不触 chainMapper.selectCount 的 in id）
        when(installMapper.selectList(any())).thenReturn(List.of());
        when(installMapper.insert(any(DatabusInstall.class))).thenAnswer(inv -> {
            insertedInstalls.add(inv.getArgument(0));
            return 1;
        });
        when(chainMapper.insert(any(DatabusChain.class))).thenAnswer(inv -> {
            DatabusChain copy = inv.getArgument(0);
            copy.setId(idSeq.incrementAndGet());
            insertedChains.add(copy);
            return 1;
        });
    }

    /**
     * 嵌套 A→B→C 全 fork（decisions §十四-1）：闭包 post-order（C/B/A）逐条复制独立成行
     * 顶层平铺、u{ownerId}__ 前缀命名、sourceChainId 逐条溯源、草稿态、三字段重定向
     * （B' EL 引 u1__chainC、A' EL 引 u1__chainB、A' 画布 CHAIN 节点 cmpId 改写）、
     * 台账仅记主链一条且 localResourceId 指向 A' 副本。
     */
    @Test
    void install_nestedAbc_forksAllIndependentRows() {
        DatabusMarketInstallVo vo = service.install(ITEM_ID, DIRECTORY_ID, USER_ID);

        // post-order 复制顺序：被引用者先落库（C'、B'、A'），主链居末
        assertEquals(3, insertedChains.size(), "闭包应逐条复制三行");
        assertEquals("u1__chainC", insertedChains.get(0).getChainCode());
        assertEquals("u1__chainB", insertedChains.get(1).getChainCode());
        assertEquals("u1__chainA", insertedChains.get(2).getChainCode());

        // 溯源标记（decisions §十四-2/5）：主链与级联副本同口径指向源链
        assertEquals(Long.valueOf(3L), insertedChains.get(0).getSourceChainId());
        assertEquals(Long.valueOf(2L), insertedChains.get(1).getSourceChainId());
        assertEquals(Long.valueOf(1L), insertedChains.get(2).getSourceChainId());

        // 副本落草稿态：可编辑的普通顶层链（decisions §十四-1）
        for (DatabusChain copy : insertedChains) {
            assertEquals(Integer.valueOf(1), copy.getVersion());
            assertEquals("0", copy.getStatus());
            assertEquals("0", copy.getIsTemplate());
            assertEquals(Long.valueOf(DIRECTORY_ID), copy.getDirectoryId());
        }

        // EL 重生成后引用重定向（decisions §十四-3）：新 code 出现、旧 code 不再是独立 token
        String elB = insertedChains.get(1).getElExpression();
        assertTrue(tokens(elB).contains("u1__chainC"), "B' 的 CHAIN 引用应为新 code: " + elB);
        assertTrue(!tokens(elB).contains("chainC"), "B' 的旧引用应消失: " + elB);
        String elA = insertedChains.get(2).getElExpression();
        assertTrue(tokens(elA).contains("u1__chainB"), "A' 的 CHAIN 引用应为新 code: " + elA);
        assertTrue(!tokens(elA).contains("chainB"), "A' 的旧引用应消失: " + elA);

        // 画布 CHAIN 节点 data.cmpId 同步改写（前端双击下钻落副本链），普通节点不动
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode nodesA = mapper.readTree(insertedChains.get(2).getCanvasData()).path("nodes");
        assertEquals("a", nodesA.path(0).path("data").path("cmpId").asText(null), "普通节点不应被误伤");
        assertEquals("u1__chainB", nodesA.path(1).path("data").path("cmpId").asText(null), "A' 画布 CHAIN 引用应改写");
        JsonNode nodesB = mapper.readTree(insertedChains.get(1).getCanvasData()).path("nodes");
        assertEquals("u1__chainC", nodesB.path(1).path("data").path("cmpId").asText(null), "B' 画布 CHAIN 引用应改写");

        // 台账仅记主链（decisions §十四-5：级联副本不写 databus_install）
        assertEquals(1, insertedInstalls.size(), "台账应仅记主链一条");
        assertEquals(insertedChains.get(2).getId(), insertedInstalls.get(0).getLocalResourceId());
        assertEquals("FORK", insertedInstalls.get(0).getMode());
        assertEquals(insertedChains.get(2).getId(), vo.getLocalResourceId());
        assertEquals("FORK", vo.getMode());

        // 级联清单 post-order [C, B, A]，全 NEW，主链居末
        assertEquals(3, vo.getCascade().size());
        assertCascade(vo.getCascade().get(0), "chainC", "u1__chainC", "NEW");
        assertCascade(vo.getCascade().get(1), "chainB", "u1__chainB", "NEW");
        assertCascade(vo.getCascade().get(2), "chainA", "u1__chainA", "NEW");
    }

    /**
     * 同源干净副本复用（decisions §十四-2、§十五-6）：装 B 时候选按「我名下＋指向同一源链＋
     * 装完没碰过」查得既有副本 → 复用该行不复制（REUSED），仅 C' 新复制；
     * 台账 localResourceId 指向复用的既有行。
     */
    @Test
    void install_sameSourceCopy_reused() {
        when(marketItemMapper.selectById(ITEM_ID)).thenReturn(item(ITEM_ID, 2L));

        DatabusChain existing = new DatabusChain();
        existing.setId(99L);
        existing.setChainCode("u1__chainB");
        existing.setSourceChainId(2L);
        // 复用探测按 source_chain_id 查：源链 2（B）命中既有副本，源链 3（C）无副本
        when(chainMapper.selectList(any())).thenAnswer(inv -> {
            AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue(2L) ? List.of(existing) : List.of();
        });

        DatabusMarketInstallVo vo = service.install(ITEM_ID, DIRECTORY_ID, USER_ID);

        // 仅 C' 新复制，B 命中同源 fork 品不复制
        assertEquals(1, insertedChains.size());
        assertEquals("u1__chainC", insertedChains.get(0).getChainCode());
        assertEquals(Long.valueOf(3L), insertedChains.get(0).getSourceChainId());

        // 台账主链指向复用的既有行
        assertEquals(1, insertedInstalls.size());
        assertEquals(Long.valueOf(99L), insertedInstalls.get(0).getLocalResourceId());
        assertEquals(Long.valueOf(99L), vo.getLocalResourceId());

        // 级联清单：C NEW、B REUSED（newCode 即既有副本编码）
        assertEquals(2, vo.getCascade().size());
        assertCascade(vo.getCascade().get(0), "chainC", "u1__chainC", "NEW");
        assertCascade(vo.getCascade().get(1), "chainB", "u1__chainB", "REUSED");
    }

    /**
     * 异源撞名（含存量 NULL，decisions §十四-2 兜底）：候选码已存在但非同源 fork 品 →
     * buildUniqueCode 后缀唯一化（u1__chainB_2），引用者改写指向兜底新码。
     */
    @Test
    void install_crossSourceCollision_fallsBackToSuffix() {
        chainCodeCounts.put("u1__chainB", 1L);
        when(chainMapper.selectOne(any())).thenReturn(null);

        DatabusMarketInstallVo vo = service.install(ITEM_ID, DIRECTORY_ID, USER_ID);

        assertEquals(3, insertedChains.size());
        assertEquals("u1__chainB_2", insertedChains.get(1).getChainCode(), "异源撞名应走后缀唯一化");

        // A' 引用改写为兜底新码（映射对引用者生效，不断链）
        String elA = insertedChains.get(2).getElExpression();
        assertTrue(tokens(elA).contains("u1__chainB_2"), "A' 应引用兜底新码: " + elA);
        assertTrue(!tokens(elA).contains("chainB"), "A' 的旧引用应消失: " + elA);

        // 级联清单 B 仍标 NEW（本次新复制，只是编码带后缀）
        assertCascade(vo.getCascade().get(1), "chainB", "u1__chainB_2", "NEW");
    }

    /**
     * 闭包市场校验拒装（decisions §十四-4）：子链 C 无市场行 → 报具体链名，且先于任何
     * 写入（链路零 insert、台账零 insert）——闸门前置保证不产生半安装状态。
     */
    @Test
    void install_subChainNotOnMarket_rejectsBeforeAnyInsert() {
        when(marketItemMapper.selectList(any())).thenReturn(List.of(item(ITEM_ID, 1L)));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.install(ITEM_ID, DIRECTORY_ID, USER_ID));

        assertEquals("依赖链路'链C'市场状态为未上架，暂不可安装", ex.getMessage());
        verify(chainMapper, never()).insert(any(DatabusChain.class));
        verify(installMapper, never()).insert(any(DatabusInstall.class));
    }

    /**
     * install 事务语义（decisions §十四-5：闭包复制与台账同事务）：@Transactional 声明
     * rollbackFor=Exception.class；复制中途异常（第 2 条链落库失败）原样传播出方法边界
     * （容器代理据此回滚，纯 Mockito 断言异常传播＋台账零写入）。
     */
    @Test
    void install_copyFailurePropagatesAndNoLedgerRow() throws Exception {
        Method install = MarketInstallService.class.getMethod("install", Long.class, Long.class, Long.class);
        Transactional tx = install.getAnnotation(Transactional.class);
        assertNotNull(tx, "install 必须声明事务");
        assertArrayEquals(new Class<?>[]{Exception.class}, tx.rollbackFor());

        AtomicInteger calls = new AtomicInteger();
        when(chainMapper.insert(any(DatabusChain.class))).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new ServiceException("模拟复制中途失败");
            }
            DatabusChain copy = inv.getArgument(0);
            copy.setId(idSeq.incrementAndGet());
            insertedChains.add(copy);
            return 1;
        });

        ServiceException ex = assertThrows(ServiceException.class,
            () -> service.install(ITEM_ID, DIRECTORY_ID, USER_ID));

        assertEquals("模拟复制中途失败", ex.getMessage());
        assertEquals(1, insertedChains.size(), "第 2 条链失败即中断，仅 C' 已落库（事务回滚清场）");
        verify(installMapper, never()).insert(any(DatabusInstall.class));
    }

    // ---------------------------- 桩构造 ----------------------------

    /**
     * 提取 EL 标识符 token 列表：\w+ 整词匹配，「u1__chainB」是单 token，
     * 避免子串包含导致的假失败/假通过。
     */
    private static List<String> tokens(String el) {
        List<String> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\w+").matcher(el);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private static void assertCascade(DatabusMarketInstallVo.CascadeItemVo item,
                                      String sourceCode, String newCode, String status) {
        assertEquals(sourceCode, item.getSourceCode());
        assertEquals(newCode, item.getNewCode());
        assertEquals(status, item.getStatus());
    }

    private static DatabusChain chain(Long id, String code, String name, CmpProperty tree, String canvas) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setCmpProperty(tree);
        chain.setCanvasData(canvas);
        return chain;
    }

    /**
     * 闭包桩（post-order，主链居末）：LinkedHashMap 保序，entry 顺序即复制顺序。
     */
    private static LinkedHashMap<String, DatabusChain> closure(DatabusChain... chains) {
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        for (DatabusChain chain : chains) {
            closure.put(chain.getChainCode(), chain);
        }
        return closure;
    }

    private static CmpProperty node(String id) {
        return CmpProperty.builder().id(id).type("NodeComponent").build();
    }

    private static CmpProperty chainRef(String id) {
        return CmpProperty.builder().id(id).type("CHAIN").build();
    }

    private static CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }

    /**
     * 画布桩：nodes[0]=普通组件节点、nodes[1]=CHAIN 引用节点（componentId null 时单节点）。
     */
    private static String canvas(String componentId, String chainId) {
        StringBuilder sb = new StringBuilder("{\"nodes\":[");
        sb.append("{\"id\":\"n1\",\"type\":\"cmp\",\"data\":{\"defType\":\"COMPONENT\",\"cmpId\":\"").append(componentId).append("\"}}");
        if (chainId != null) {
            sb.append(",{\"id\":\"n2\",\"type\":\"cmp\",\"data\":{\"defType\":\"CHAIN\",\"cmpId\":\"").append(chainId).append("\"}}");
        }
        return sb.append("],\"edges\":[]}").toString();
    }

    private static DatabusMarketItem item(Long id, Long sourceId) {
        DatabusMarketItem item = new DatabusMarketItem();
        item.setId(id);
        item.setResourceType("CHAIN");
        item.setSourceId(sourceId);
        item.setStatus("ONLINE");
        return item;
    }
}
