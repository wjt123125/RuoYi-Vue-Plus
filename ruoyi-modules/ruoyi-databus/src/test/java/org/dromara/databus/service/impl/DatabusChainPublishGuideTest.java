package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.ChainPublishResultVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 块5.1 发布引导单测（decisions §十四-6）：publish 闭包内未上架物料收集——
 * 自己的子链/组件进引导清单（随发布响应返回供前端弹窗批量提审）、他人的链无市场行
 * 拒发布（无权代提，拒绝先于任何变更）、他人的组件未上架不阻塞只列名提示、
 * 主链排除引导桶（受审主体即主链自身）、已有市场行＝已上架（PENDING 在审不算未上架，
 * 改版冻结态不误拒）、悬空组件引用跳过（终审 gate 拒，两级口径）。
 * <p>
 * 纯 JUnit5＋Mockito 直构 Service：chainMapper.selectOne 按 wrapper 里的 chainCode 回查
 * 内存桩表（walkChainReferences 与 collectChainClosure 同 stub 多次命中）。
 *
 * @author databus
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DatabusChainPublishGuideTest {

    @Mock
    private DatabusChainMapper chainMapper;

    @Mock
    private ExpressGenerator expressGenerator;

    @Mock
    private RulePublishService rulePublishService;

    @Mock
    private DatabusMarketItemMapper marketItemMapper;

    @Mock
    private DatabusComponentMapper componentMapper;

    private DatabusChainServiceImpl chainService;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 环境下 wrapper 的 lambda 列解析依赖实体 TableInfo 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusChain.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusMarketItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusComponent.class);
    }

    @BeforeEach
    void setUp() {
        chainService = new DatabusChainServiceImpl(chainMapper, expressGenerator, rulePublishService,
            marketItemMapper, componentMapper);
    }

    /**
     * 场景1：闭包内他人子链（chainC，create_by=99）无市场行 → 发布拒绝；
     * 链维度拒绝先于 Rule-DB 推送/状态更新（gate 必须发生在任何变更前）
     */
    @Test
    void publish_otherUnlistedChain_rejected() {
        DatabusChain main = mainChain();
        when(chainMapper.selectById(1L)).thenReturn(main);
        stubChains(main, subChainB(), subChainC());
        when(marketItemMapper.selectList(any())).thenReturn(List.of());
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(...);"));

        ServiceException ex = assertThrows(ServiceException.class, () -> chainService.publish(1L, 1L));
        // 闭包顺序 chainB 先（自己、无行本应进引导），chainC 后（他人、无行→拒）——
        // 拒绝抛出后引导清单整体作废（前端弹窗不会出现）
        assertTrue(ex.getMessage().contains("无权代他人提审"), "应拒他人未上架链：" + ex.getMessage());
        verify(rulePublishService, never()).publishChain(any(), any(), any());
        verify(chainMapper, never()).updateById(any(DatabusChain.class));
    }

    /**
     * 场景2：自己的未上架子链（chainB）＋组件（compX）进引导清单；他人组件（compY）未上架
     * 不阻塞只列名提示；他人子链（chainC）已有 PENDING 市场行＝已上架不算未上架；
     * 主链自身（无市场行）排除引导桶——发布照常成功，清单随响应返回
     */
    @Test
    void publish_ownUnlistedIntoGuide() {
        DatabusChain main = mainChain();
        when(chainMapper.selectById(1L)).thenReturn(main);
        stubChains(main, subChainB(), subChainC());
        // 链维度：chainC 有 PENDING 行（在审＝已上架）；组件维度：全部无行
        when(marketItemMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusMarketItem> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("CHAIN")
                ? List.of(marketItem(30L, "CHAIN", 3L, "PENDING"))
                : List.of();
        });
        when(componentMapper.selectList(any())).thenReturn(List.of(
            component(10L, "compX", "组件X", 1L), component(11L, "compY", "组件Y", 99L)));
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(...);"));
        when(chainMapper.updateById(any(DatabusChain.class))).thenReturn(1);

        ChainPublishResultVo result = chainService.publish(1L, 1L);

        assertNotNull(result);
        // 引导顺序：链维度先收（chainB）、组件维度后收（compX）
        assertEquals(2, result.getGuideItems().size());
        assertEquals("CHAIN", result.getGuideItems().get(0).getResourceType());
        assertEquals(2L, result.getGuideItems().get(0).getSourceId());
        assertEquals("链B", result.getGuideItems().get(0).getName());
        assertEquals("COMPONENT", result.getGuideItems().get(1).getResourceType());
        assertEquals(10L, result.getGuideItems().get(1).getSourceId());
        assertEquals("组件X", result.getGuideItems().get(1).getName());
        // 主链（sourceId 1，无市场行）与已上架 chainC（sourceId 3，PENDING）都不进引导桶
        assertTrue(result.getGuideItems().stream()
            .noneMatch(g -> Objects.equals(1L, g.getSourceId()) || Objects.equals(3L, g.getSourceId())));
        // 他人组件未上架：不阻塞只提示
        assertEquals(List.of("组件Y"), result.getOtherUnlistedComponents());
        verify(rulePublishService).publishChain(any(), any(), any());
    }

    /**
     * 场景3：闭包组件引用悬空（componentMapper 查无行）→ 引导只建议不拦（两级口径：
     * 终审 gate 拒「组件已不存在」），发布照常成功、清单为空
     */
    @Test
    void publish_danglingComponentSkipped() {
        DatabusChain main = chain(1L, "chainD", "链D", ChainStatusEnum.DRAFT.getCode(), 1L,
            then(node("compD")));
        stubChains(main);
        when(chainMapper.selectById(1L)).thenReturn(main);
        when(marketItemMapper.selectList(any())).thenReturn(List.of());
        when(componentMapper.selectList(any())).thenReturn(List.of());
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(...);"));
        when(chainMapper.updateById(any(DatabusChain.class))).thenReturn(1);

        ChainPublishResultVo result = chainService.publish(1L, 1L);

        assertNotNull(result);
        assertTrue(result.getGuideItems().isEmpty());
        assertTrue(result.getOtherUnlistedComponents().isEmpty());
        verify(rulePublishService).publishChain(any(), any(), any());
    }

    // ---------------------------- fixtures ----------------------------

    /**
     * 主链：THEN( 引用 chainB、引用 chainC、组件 compX )——闭包＝{chainB, chainC, chainA}
     */
    private DatabusChain mainChain() {
        return chain(1L, "chainA", "链A", ChainStatusEnum.DRAFT.getCode(), 1L,
            then(chainRef("chainB"), chainRef("chainC"), node("compX")));
    }

    /**
     * 子链 chainB（create_by=1 本人）：THEN( 组件 compY )——已发布（被主链引用的前提）
     */
    private DatabusChain subChainB() {
        return chain(2L, "chainB", "链B", ChainStatusEnum.PUBLISHED.getCode(), 1L,
            then(node("compY")));
    }

    /**
     * 子链 chainC（create_by=99 他人）：已发布
     */
    private DatabusChain subChainC() {
        return chain(3L, "chainC", "链C", ChainStatusEnum.PUBLISHED.getCode(), 99L,
            then(node("compZ")));
    }

    /**
     * 按 chainCode 回查内存桩表：walkChainReferences（发布子链校验）与
     * collectChainClosure（引导闭包收集）共用同一 stub。
     */
    private void stubChains(DatabusChain... chains) {
        when(chainMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusChain> wrapper = inv.getArgument(0);
            // eq 条件值懒物化（formatParam 在取 SQL 段时才写入 paramNameValuePairs），先取段再回读
            wrapper.getSqlSegment();
            Object code = wrapper.getParamNameValuePairs().values().iterator().next();
            for (DatabusChain c : chains) {
                if (c.getChainCode().equals(code)) {
                    return c;
                }
            }
            return null;
        });
    }

    private DatabusChain chain(Long id, String code, String name, String status, Long createBy, CmpProperty tree) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setStatus(status);
        chain.setCreateBy(createBy);
        chain.setVersion(1);
        chain.setIsTemplate("0");
        chain.setCmpProperty(tree);
        return chain;
    }

    private DatabusComponent component(Long id, String code, String name, Long createBy) {
        DatabusComponent component = new DatabusComponent();
        component.setId(id);
        component.setComponentCode(code);
        component.setComponentName(name);
        component.setCreateBy(createBy);
        return component;
    }

    private DatabusMarketItem marketItem(Long id, String resourceType, Long sourceId, String status) {
        DatabusMarketItem item = new DatabusMarketItem();
        item.setId(id);
        item.setResourceType(resourceType);
        item.setSourceId(sourceId);
        item.setStatus(status);
        return item;
    }

    private ELInfo elInfo(String elStr) {
        ELInfo info = new ELInfo();
        info.setElStr(elStr);
        return info;
    }

    /** 业务组件叶子（非 EL 结构关键字） */
    private CmpProperty node(String code) {
        return CmpProperty.builder().id(code).type("NodeComponent").build();
    }

    /** CHAIN 引用叶子（顶层 id 即被引链 code） */
    private CmpProperty chainRef(String code) {
        return CmpProperty.builder().id(code).type("CHAIN").build();
    }

    private CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }
}
