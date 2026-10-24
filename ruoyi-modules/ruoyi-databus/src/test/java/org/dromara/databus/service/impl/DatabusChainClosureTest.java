package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * collectChainClosure 闭包收集单元测试（decisions §十四-1 链路安装全 fork）。
 * <p>
 * 纯 JUnit5＋Mockito 直构 Service（冒烟上下文不含 Service 层，走不起 Spring）：
 * chainMapper.selectOne 按包装参数里的 chainCode 回查内存桩表。覆盖 plan §8 块1 验证矩阵——
 * 嵌套 post-order 顺序、环拒绝回显路径、四类拒绝文案（与发布侧逐字对齐）、菱形剪枝、
 * 历史数据 properties.id 兜底。
 *
 * @author databus
 */
@Tag("dev")
class DatabusChainClosureTest {

    private final Map<String, DatabusChain> chainsByCode = new HashMap<>();

    private DatabusChainMapper chainMapper;

    private DatabusChainServiceImpl service;

    /**
     * 纯 Mockito 环境无 Spring/MyBatis 装配：手动注册实体元数据，LambdaQueryWrapper 的
     * lambda 列解析才不会抛「can not find lambda cache」（TableInfoHelper 全局缓存，幂等）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusChain.class);
    }

    @BeforeEach
    void setUp() {
        chainsByCode.clear();
        chainMapper = mock(DatabusChainMapper.class);
        when(chainMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusChain> wrapper = inv.getArgument(0);
            // eq 条件值懒物化（formatParam 在取 SQL 段时才写入 paramNameValuePairs），先取段再回读
            wrapper.getSqlSegment();
            Object code = wrapper.getParamNameValuePairs().values().iterator().next();
            return chainsByCode.get(code);
        });
        service = new DatabusChainServiceImpl(chainMapper, mock(ExpressGenerator.class),
            mock(RulePublishService.class), mock(DatabusMarketItemMapper.class), mock(DatabusComponentMapper.class));
    }

    /**
     * 嵌套 A→B→C：post-order 收集（被引用者先于引用者），主链最后入列——即块3 复制施工顺序。
     */
    @Test
    void collectChainClosure_nested_returnsPostOrder() {
        chain("c", "链C", then());
        chain("b", "链B", then(chainRef("c")));
        chain("a", "链A", then(chainRef("b")));

        LinkedHashMap<String, DatabusChain> closure = service.collectChainClosure("a");

        assertEquals(List.of("c", "b", "a"), new ArrayList<>(closure.keySet()));
        assertSame(chainsByCode.get("a"), closure.get("a"));
        assertSame(chainsByCode.get("c"), closure.get("c"));
    }

    /**
     * 菱形 A→B/C、B→D、C→D：D 只展开一次（黑色集剪枝），闭包仍为全量四条。
     */
    @Test
    void collectChainClosure_diamond_prunesSharedSub() {
        chain("d", "链D", then());
        chain("b", "链B", then(chainRef("d")));
        chain("c", "链C", then(chainRef("d")));
        chain("a", "链A", then(chainRef("b"), chainRef("c")));

        LinkedHashMap<String, DatabusChain> closure = service.collectChainClosure("a");

        assertEquals(List.of("d", "b", "c", "a"), new ArrayList<>(closure.keySet()));
        // 查库 4 次（a/b/c/d 各一次）；无剪枝则 d 会被查两次（5 次）
        verify(chainMapper, times(4)).selectOne(any());
    }

    /**
     * 历史数据兜底：CHAIN 叶子 chainCode 只在 properties.id（顶层 id 缺失）也能收集。
     */
    @Test
    void collectChainClosure_legacyPropertiesId_stillCollected() {
        chain("b", "链B", then());
        chain("a", "链A", then(chainRefLegacy("b")));

        LinkedHashMap<String, DatabusChain> closure = service.collectChainClosure("a");

        assertEquals(List.of("b", "a"), new ArrayList<>(closure.keySet()));
    }

    /**
     * 互相引用成环 A→B→A：拒绝且回显完整环路径（与发布侧同法，仅动词适配安装场景）。
     */
    @Test
    void collectChainClosure_cycle_rejectsWithPath() {
        chain("b", "链B", then(chainRef("a")));
        chain("a", "链A", then(chainRef("b")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("子流程循环引用，不能安装：a → b → a", ex.getMessage());
    }

    /**
     * 自引用 A→A 同样成环：回显 a → a。
     */
    @Test
    void collectChainClosure_selfReference_rejects() {
        chain("a", "链A", then(chainRef("a")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("子流程循环引用，不能安装：a → a", ex.getMessage());
    }

    /**
     * 四类拒绝之一：子链不存在。
     */
    @Test
    void collectChainClosure_missingSub_rejects() {
        chain("a", "链A", then(chainRef("ghost")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("引用的子流程'ghost'已不存在，请检查引用或移除该节点", ex.getMessage());
    }

    /**
     * 四类拒绝之二：子链是精选模板（模板红线与发布侧同口径）。
     */
    @Test
    void collectChainClosure_templateSub_rejects() {
        DatabusChain b = chain("b", "链B", then());
        b.setIsTemplate("1");
        chain("a", "链A", then(chainRef("b")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("子流程'b'是精选模板，运行时不可被引用，请复制副本后引用副本", ex.getMessage());
    }

    /**
     * 四类拒绝之三：子链未发布。
     */
    @Test
    void collectChainClosure_unpublishedSub_rejects() {
        DatabusChain b = chain("b", "链B", then());
        b.setStatus(ChainStatusEnum.DRAFT.getCode());
        chain("a", "链A", then(chainRef("b")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("子流程'b'尚未发布，请先发布它再发布本链路", ex.getMessage());
    }

    /**
     * 四类拒绝之四：子链未编排（cmpProperty 为空）。
     */
    @Test
    void collectChainClosure_unarrangedSub_rejects() {
        chain("b", "链B", null);
        chain("a", "链A", then(chainRef("b")));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("a"));

        assertEquals("子流程'b'未编排组件，请先补全它的编排", ex.getMessage());
    }

    /**
     * 根链不存在：直接拒绝。
     */
    @Test
    void collectChainClosure_rootMissing_rejects() {
        ServiceException ex = assertThrows(ServiceException.class, () -> service.collectChainClosure("ghost"));

        assertEquals("链路'ghost'不存在或已删除", ex.getMessage());
    }

    // ---------------------------- 桩构造 ----------------------------

    private DatabusChain chain(String code, String name, CmpProperty cmpProperty) {
        DatabusChain chain = new DatabusChain();
        chain.setId((long) (100 + chainsByCode.size()));
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setStatus(ChainStatusEnum.PUBLISHED.getCode());
        chain.setIsTemplate("0");
        chain.setCmpProperty(cmpProperty);
        chainsByCode.put(code, chain);
        return chain;
    }

    /**
     * CHAIN 引用节点：chainCode 存顶层 id（buildChildrenChain 的 .id(chainCode)）。
     */
    private CmpProperty chainRef(String code) {
        return CmpProperty.builder().id(code).type("CHAIN").build();
    }

    /**
     * CHAIN 引用节点（历史数据兜底形态）：chainCode 只在 properties.id。
     */
    private CmpProperty chainRefLegacy(String code) {
        return CmpProperty.builder().type("CHAIN")
            .properties(Properties.builder().id(code).build())
            .build();
    }

    /**
     * THEN(children) 容器节点。
     */
    private CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }
}
