package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketPublishBatchBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.service.IDatabusChainService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 块5.3 终审 gate＋审核详情＋批量提审单测（decisions §十四-7 嵌套审核）：approve CHAIN 物料
 * 前闭包内全部依赖（链＋组件，全量卡不豁免系统资源——用户拍板推翻 plan §7.3 豁免推荐）市场
 * 状态必须 ONLINE；链依赖「未上架/未过审」两段报错、组件依赖多一段「已不存在」（悬空引用）；
 * 主链即受审主体不算依赖；驳回与非 CHAIN 物料不走 gate。reviewDetail 与 gate 同口径组装
 * 依赖状态树（悬空组件 sourceId 退化为 null、name 退化为编码）；publishBatch 整批
 * all-or-nothing（中途被拒异常传播，已处理项随事务回滚）。
 * <p>
 * 纯 JUnit5＋Mockito 直构 Service：chainService 以接口 mock 直接喂闭包结果
 * （collectChainClosure 已由 DatabusChainClosureTest 覆盖），marketItemMapper.selectList
 * 按 wrapper 内 resourceType 参数值分流 CHAIN/COMPONENT 两次查询。
 *
 * @author databus
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DatabusMarketReviewGateTest {

    @Mock
    private DatabusMarketItemMapper marketItemMapper;

    @Mock
    private DatabusMarketCategoryMapper marketCategoryMapper;

    @Mock
    private DatabusInstallMapper installMapper;

    @Mock
    private DatabusChainMapper chainMapper;

    @Mock
    private DatabusComponentMapper componentMapper;

    @Mock
    private DatabusConnectorDefMapper connectorDefMapper;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private IDatabusChainService chainService;

    @Mock
    private ExpressGenerator expressGenerator;

    private DatabusMarketServiceImpl marketService;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 环境下 wrapper 的 lambda 列解析依赖实体 TableInfo 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusMarketItem.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusComponent.class);
    }

    @BeforeEach
    void setUp() {
        marketService = new DatabusMarketServiceImpl(marketItemMapper, marketCategoryMapper, installMapper,
            chainMapper, componentMapper, connectorDefMapper, objectMapper, chainService, expressGenerator);
    }

    /**
     * 场景1：闭包子链（chainB）有市场行但 PENDING → approve 拒「未过审」，台账不动
     */
    @Test
    void review_chainGatePending_rejected() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        when(chainMapper.selectById(1L)).thenReturn(rootWithSubChain());
        when(chainService.collectChainClosure("chainA")).thenReturn(closureWithSubChain());
        when(marketItemMapper.selectList(any())).thenReturn(List.of(marketItem(31L, "CHAIN", 2L, "PENDING")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.review(50L, reviewBo(true, null), 3L));

        assertTrue(ex.getMessage().contains("依赖链路'链B'未过审（市场状态：PENDING）"),
            "应拒未过审依赖：" + ex.getMessage());
        verify(marketItemMapper, never()).updateById(any(DatabusMarketItem.class));
    }

    /**
     * 场景2：闭包子链无市场行 → approve 拒「未上架」
     */
    @Test
    void review_chainGateUnlisted_rejected() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        when(chainMapper.selectById(1L)).thenReturn(rootWithSubChain());
        when(chainService.collectChainClosure("chainA")).thenReturn(closureWithSubChain());
        when(marketItemMapper.selectList(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.review(50L, reviewBo(true, null), 3L));

        assertTrue(ex.getMessage().contains("依赖链路'链B'未上架市场"),
            "应拒未上架子链：" + ex.getMessage());
        verify(marketItemMapper, never()).updateById(any(DatabusMarketItem.class));
    }

    /**
     * 场景3：闭包内组件引用悬空（componentMapper 查无行）→ approve 拒「已不存在」
     * （报错用引用编码 compZ，非组件名）
     */
    @Test
    void review_componentGateDangling_rejected() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        DatabusChain root = chain(1L, "chainA", "链A", then(node("compZ")));
        when(chainMapper.selectById(1L)).thenReturn(root);
        when(chainService.collectChainClosure("chainA")).thenReturn(closureOf(root));
        when(componentMapper.selectList(any())).thenReturn(List.of());

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.review(50L, reviewBo(true, null), 3L));

        assertTrue(ex.getMessage().contains("依赖组件'compZ'已不存在"),
            "应拒悬空组件：" + ex.getMessage());
    }

    /**
     * 场景4：闭包内组件有市场行但 PENDING → approve 拒「未过审」（组件市场状态用组件名）
     */
    @Test
    void review_componentGatePending_rejected() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        DatabusChain root = chain(1L, "chainA", "链A", then(node("compZ")));
        when(chainMapper.selectById(1L)).thenReturn(root);
        when(chainService.collectChainClosure("chainA")).thenReturn(closureOf(root));
        when(componentMapper.selectList(any())).thenReturn(List.of(component(10L, "compZ", "组件Z")));
        when(marketItemMapper.selectList(any())).thenReturn(List.of(marketItem(40L, "COMPONENT", 10L, "PENDING")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.review(50L, reviewBo(true, null), 3L));

        assertTrue(ex.getMessage().contains("依赖组件'组件Z'未过审（市场状态：PENDING）"),
            "应拒未过审组件：" + ex.getMessage());
    }

    /**
     * 场景5：闭包子链＋组件市场行全 ONLINE → gate 放行，审核通过置 ONLINE＋reviewBy 落审核人
     */
    @Test
    void review_allOnline_passes() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        when(chainMapper.selectById(1L)).thenReturn(rootWithSubChain());
        when(chainService.collectChainClosure("chainA")).thenReturn(closureWithSubChain());
        // 链维度 [31 ONLINE] / 组件维度 [40 compX ONLINE, 41 compY ONLINE] 分流
        when(marketItemMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusMarketItem> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("CHAIN")
                ? List.of(marketItem(31L, "CHAIN", 2L, "ONLINE"))
                : List.of(marketItem(40L, "COMPONENT", 10L, "ONLINE"), marketItem(41L, "COMPONENT", 11L, "ONLINE"));
        });
        // 闭包组件并集＝chainB 的 compY＋主链的 compX（CHAIN 叶子不在组件清单）
        when(componentMapper.selectList(any())).thenReturn(List.of(
            component(10L, "compX", "组件X"), component(11L, "compY", "组件Y")));
        when(marketItemMapper.updateById(any(DatabusMarketItem.class))).thenReturn(1);

        Boolean result = marketService.review(50L, reviewBo(true, null), 3L);

        assertTrue(result);
        ArgumentCaptor<DatabusMarketItem> captor = ArgumentCaptor.forClass(DatabusMarketItem.class);
        verify(marketItemMapper).updateById(captor.capture());
        assertEquals("ONLINE", captor.getValue().getStatus());
        assertEquals(3L, captor.getValue().getReviewBy());
    }

    /**
     * 场景6：驳回路径（approved=false）不走 gate——不查源链不查闭包
     */
    @Test
    void review_rejectPath_skipsGate() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        when(marketItemMapper.updateById(any(DatabusMarketItem.class))).thenReturn(1);

        Boolean result = marketService.review(50L, reviewBo(false, "不合规"), 3L);

        assertTrue(result);
        verify(chainMapper, never()).selectById(anyLong());
        ArgumentCaptor<DatabusMarketItem> captor = ArgumentCaptor.forClass(DatabusMarketItem.class);
        verify(marketItemMapper).updateById(captor.capture());
        assertEquals("REJECTED", captor.getValue().getStatus());
        assertEquals("不合规", captor.getValue().getRemark());
    }

    /**
     * 场景7：非 CHAIN 物料（COMPONENT）approve 不走 gate 直接过审
     */
    @Test
    void review_nonChain_skipsGate() {
        DatabusMarketItem componentItem = pendingChainItem();
        componentItem.setResourceType("COMPONENT");
        when(marketItemMapper.selectById(50L)).thenReturn(componentItem);
        when(marketItemMapper.updateById(any(DatabusMarketItem.class))).thenReturn(1);

        Boolean result = marketService.review(50L, reviewBo(true, null), 3L);

        assertTrue(result);
        verify(chainMapper, never()).selectById(anyLong());
        ArgumentCaptor<DatabusMarketItem> captor = ArgumentCaptor.forClass(DatabusMarketItem.class);
        verify(marketItemMapper).updateById(captor.capture());
        assertEquals("ONLINE", captor.getValue().getStatus());
    }

    /**
     * 场景8：受审 CHAIN 物料源链已删 → approve 拒（审核者背书不了悬空源）
     */
    @Test
    void review_sourceChainMissing_rejected() {
        when(marketItemMapper.selectById(50L)).thenReturn(pendingChainItem());
        when(chainMapper.selectById(1L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.review(50L, reviewBo(true, null), 3L));

        assertTrue(ex.getMessage().contains("源链路不存在或已删除，无法审核通过"),
            "应拒源链悬空：" + ex.getMessage());
    }

    /**
     * 场景9：reviewDetail 组装依赖状态树——链维度先（ONLINE）、组件维度后（闭包遍历
     * chainB 先于主链：compY ONLINE 在前、compX PENDING 带驳回理由 remark 在后）；
     * 主链（sourceId 1）即受审主体不列入依赖
     */
    @Test
    void reviewDetail_assemblesDependencies() {
        DatabusMarketItem item = pendingChainItem();
        item.setId(60L);
        when(marketItemMapper.selectById(60L)).thenReturn(item);
        when(chainMapper.selectById(1L)).thenReturn(rootWithSubChain());
        when(chainService.collectChainClosure("chainA")).thenReturn(closureWithSubChain());
        when(marketItemMapper.selectList(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusMarketItem> wrapper = inv.getArgument(0);
            wrapper.getSqlSegment();
            return wrapper.getParamNameValuePairs().containsValue("CHAIN")
                ? List.of(marketItem(31L, "CHAIN", 2L, "ONLINE"))
                : List.of(marketItem(41L, "COMPONENT", 11L, "ONLINE"),
                    marketItem(40L, "COMPONENT", 10L, "PENDING", "脚本待改"));
        });
        when(componentMapper.selectList(any())).thenReturn(List.of(
            component(10L, "compX", "组件X"), component(11L, "compY", "组件Y")));

        DatabusMarketReviewDetailVo vo = marketService.reviewDetail(60L);

        assertNotNull(vo.getItem());
        assertEquals(60L, vo.getItem().getId());
        assertEquals(3, vo.getDependencies().size());
        DatabusMarketReviewDetailVo.DependencyVo chainDep = vo.getDependencies().get(0);
        assertEquals("CHAIN", chainDep.getResourceType());
        assertEquals(2L, chainDep.getSourceId());
        assertEquals("链B", chainDep.getName());
        assertEquals(31L, chainDep.getMarketItemId());
        assertEquals("ONLINE", chainDep.getMarketStatus());
        // 组件维度顺序＝闭包遍历顺序（chainB 先收 compY、主链后收 compX）
        DatabusMarketReviewDetailVo.DependencyVo compYDep = vo.getDependencies().get(1);
        assertEquals("COMPONENT", compYDep.getResourceType());
        assertEquals(11L, compYDep.getSourceId());
        assertEquals("组件Y", compYDep.getName());
        assertEquals(41L, compYDep.getMarketItemId());
        assertEquals("ONLINE", compYDep.getMarketStatus());
        DatabusMarketReviewDetailVo.DependencyVo compDep = vo.getDependencies().get(2);
        assertEquals("COMPONENT", compDep.getResourceType());
        assertEquals(10L, compDep.getSourceId());
        assertEquals("组件X", compDep.getName());
        assertEquals(40L, compDep.getMarketItemId());
        assertEquals("PENDING", compDep.getMarketStatus());
        assertEquals("脚本待改", compDep.getRemark());
        // 主链自身即受审 item，不列入依赖（否则 PENDING 自相矛盾）
        assertTrue(vo.getDependencies().stream().noneMatch(d -> Long.valueOf(1L).equals(d.getSourceId())));
    }

    /**
     * 场景10：reviewDetail 悬空组件退化展示——sourceId/marketItemId/marketStatus 全 null、
     * name 退化为引用编码（视图可展示，gate 在 approve 时拒）
     */
    @Test
    void reviewDetail_danglingComponent_degrades() {
        DatabusMarketItem item = pendingChainItem();
        item.setId(60L);
        when(marketItemMapper.selectById(60L)).thenReturn(item);
        DatabusChain root = chain(1L, "chainA", "链A", then(node("compZ")));
        when(chainMapper.selectById(1L)).thenReturn(root);
        when(chainService.collectChainClosure("chainA")).thenReturn(closureOf(root));
        when(componentMapper.selectList(any())).thenReturn(List.of());

        DatabusMarketReviewDetailVo vo = marketService.reviewDetail(60L);

        assertEquals(1, vo.getDependencies().size());
        DatabusMarketReviewDetailVo.DependencyVo dep = vo.getDependencies().get(0);
        assertEquals("COMPONENT", dep.getResourceType());
        assertNull(dep.getSourceId());
        assertEquals("compZ", dep.getName());
        assertNull(dep.getMarketItemId());
        assertNull(dep.getMarketStatus());
    }

    /**
     * 场景11：publishBatch 第二项源链不存在 → 异常传播（整批 all-or-nothing，前端弹窗
     * 重选后整批重提），已处理的第一项 insert 只发生一次（真实环境随事务回滚）
     */
    @Test
    void publishBatch_secondItemFails_propagates() {
        MarketPublishBatchBo bo = new MarketPublishBatchBo();
        bo.setItems(List.of(publishBo("CHAIN", 1L), publishBo("CHAIN", 2L)));
        // chain fixture 默认 create_by=7（=userId，走作者本人归属分支避开 StpUtil）
        when(chainMapper.selectById(1L)).thenReturn(chain(1L, "chainA", "链A", null));
        // 第二项源链查无行
        when(chainMapper.selectById(2L)).thenReturn(null);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> marketService.publishBatch(bo, 7L, "user7"));

        assertTrue(ex.getMessage().contains("源链路不存在或已删除"), "应传播第二项失败：" + ex.getMessage());
        verify(marketItemMapper, times(1)).insert(any(DatabusMarketItem.class));
    }

    /**
     * 场景12：publishBatch 链＋组件混排全部成功（归属校验走 create_by=userId 作者本人分支，
     * 避开 StpUtil 静态调用）
     */
    @Test
    void publishBatch_allSucceed() {
        MarketPublishBatchBo bo = new MarketPublishBatchBo();
        bo.setItems(List.of(publishBo("CHAIN", 1L), publishBo("COMPONENT", 10L)));
        when(chainMapper.selectById(1L)).thenReturn(chain(1L, "chainA", "链A", null));
        when(componentMapper.selectById(10L)).thenReturn(component(10L, "compA", "组件A"));

        Boolean result = marketService.publishBatch(bo, 7L, "user7");

        assertTrue(result);
        verify(marketItemMapper, times(2)).insert(any(DatabusMarketItem.class));
    }

    // ---------------------------- fixtures ----------------------------

    /**
     * 受审 CHAIN 物料行：id 50 / 归属 ownerId 2 / 源链 sourceId 1 / PENDING
     * （审核人 3 ≠ ownerId 2，避开禁自审）
     */
    private DatabusMarketItem pendingChainItem() {
        DatabusMarketItem item = new DatabusMarketItem();
        item.setId(50L);
        item.setOwnerId(2L);
        item.setResourceType("CHAIN");
        item.setSourceId(1L);
        item.setStatus("PENDING");
        item.setName("链A");
        return item;
    }

    /**
     * 主链（chainA）：THEN( 引用 chainB、组件 compX )——闭包＝{chainB, chainA}
     */
    private DatabusChain rootWithSubChain() {
        return chain(1L, "chainA", "链A", then(chainRef("chainB"), node("compX")));
    }

    /**
     * 闭包（post-order：被引用者 chainB 先于引用者主链 chainA）
     */
    private LinkedHashMap<String, DatabusChain> closureWithSubChain() {
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        closure.put("chainB", chain(2L, "chainB", "链B", then(node("compY"))));
        closure.put("chainA", rootWithSubChain());
        return closure;
    }

    private LinkedHashMap<String, DatabusChain> closureOf(DatabusChain root) {
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        closure.put(root.getChainCode(), root);
        return closure;
    }

    private DatabusChain chain(Long id, String code, String name, CmpProperty tree) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setCreateBy(7L);
        chain.setCmpProperty(tree);
        return chain;
    }

    private DatabusComponent component(Long id, String code, String name) {
        DatabusComponent component = new DatabusComponent();
        component.setId(id);
        component.setComponentCode(code);
        component.setComponentName(name);
        component.setCreateBy(7L);
        return component;
    }

    private DatabusMarketItem marketItem(Long id, String resourceType, Long sourceId, String status) {
        return marketItem(id, resourceType, sourceId, status, null);
    }

    private DatabusMarketItem marketItem(Long id, String resourceType, Long sourceId, String status, String remark) {
        DatabusMarketItem item = new DatabusMarketItem();
        item.setId(id);
        item.setResourceType(resourceType);
        item.setSourceId(sourceId);
        item.setStatus(status);
        item.setRemark(remark);
        return item;
    }

    private MarketReviewBo reviewBo(Boolean approved, String reason) {
        MarketReviewBo bo = new MarketReviewBo();
        bo.setApproved(approved);
        bo.setReason(reason);
        return bo;
    }

    private MarketPublishBo publishBo(String resourceType, Long sourceId) {
        MarketPublishBo bo = new MarketPublishBo();
        bo.setResourceType(resourceType);
        bo.setSourceId(sourceId);
        return bo;
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
