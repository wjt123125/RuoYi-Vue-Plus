package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 块5.2 改版冻结钩子单测（decisions §十四-8）：publish 成功后指向本链的 ONLINE 市场项
 * 置回 PENDING＋清审核痕迹（install 拒装＝冻结、重审过＝解冻的两端已由块1/块3 单测覆盖）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DatabusChainPublishFreezeTest {

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

    @Mock
    private DatabusInstallMapper installMapper;

    private DatabusChainServiceImpl chainService;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito 环境下 wrapper 的 lambda 列解析依赖实体 TableInfo 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusChain.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusMarketItem.class);
        // 块5.1 起 publish 会走发布引导（collectUnlistedClosure），组件编码 in 查询需要组件 TableInfo
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusComponent.class);
    }

    @BeforeEach
    void setUp() {
        chainService = new DatabusChainServiceImpl(chainMapper, expressGenerator, rulePublishService,
            marketItemMapper, componentMapper, installMapper);
    }

    /**
     * 场景1：草稿链（已上架 ONLINE）改版发布成功 → 冻结钩子命中本链 id＋ONLINE 的市场项，
     * 置回 PENDING＋清驳回理由；链状态照常 PUBLISHED＋version+1＋EL 回写
     */
    @Test
    void publish_onlineMarketItem_freezesToPending() {
        when(chainMapper.selectById(1L)).thenReturn(chain(1L, "chainB", "链B", ChainStatusEnum.DRAFT.getCode(), 1));
        stubClosureRoot();
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(b);"));
        when(chainMapper.updateById(any(DatabusChain.class))).thenReturn(1);
        when(marketItemMapper.update(isNull(), any())).thenReturn(1);

        // 发布引导（decisions §十四-6）：闭包只有主链＋悬空组件引用（componentMapper 未 stub 返空
        // → 引导跳过），返回非 null 即发布成功
        assertNotNull(chainService.publish(1L, 1L));
        verify(rulePublishService).publishChain(any(), any(), any());

        ArgumentCaptor<DatabusChain> chainCaptor = ArgumentCaptor.forClass(DatabusChain.class);
        verify(chainMapper).updateById(chainCaptor.capture());
        assertEquals(ChainStatusEnum.PUBLISHED.getCode(), chainCaptor.getValue().getStatus());
        assertEquals(2, chainCaptor.getValue().getVersion());
        assertEquals("THEN(b);", chainCaptor.getValue().getElExpression());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<DatabusMarketItem>> wrapperCaptor =
            ArgumentCaptor.forClass((Class) LambdaUpdateWrapper.class);
        verify(marketItemMapper).update(isNull(), wrapperCaptor.capture());
        // 物化 param pairs（eq/set 的值懒写入）后按值核对：where CHAIN+1+ONLINE，set PENDING+空驳回理由
        wrapperCaptor.getValue().getSqlSegment();
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("CHAIN"), "应按市场类型 CHAIN 过滤");
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(1L), "应按本链 sourceId 过滤");
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("ONLINE"), "应只冻结在架 ONLINE 项");
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue("PENDING"), "应置回 PENDING");
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(""), "应清空驳回理由");
    }

    /**
     * 场景2：状态 gate 拒绝（已发布态不可再发布）→ 发布失败，不触冻结
     */
    @Test
    void publish_statusGateRejected_noFreeze() {
        when(chainMapper.selectById(1L)).thenReturn(chain(1L, "chainB", "链B", ChainStatusEnum.PUBLISHED.getCode(), 1));
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(b);"));

        ServiceException ex = assertThrows(ServiceException.class, () -> chainService.publish(1L, 1L));
        assertTrue(ex.getMessage().contains("当前状态不允许发布"));
        verify(chainMapper, never()).updateById(any(DatabusChain.class));
        verify(marketItemMapper, never()).update(any(), any());
    }

    /**
     * 场景3：本链无在架市场项（冻结 update 命中 0 行）→ 无副作用，发布照常成功
     */
    @Test
    void publish_noOnlineMarketItem_stillSucceeds() {
        when(chainMapper.selectById(1L)).thenReturn(chain(1L, "chainB", "链B", ChainStatusEnum.DRAFT.getCode(), 1));
        stubClosureRoot();
        when(expressGenerator.generateEL(any())).thenReturn(elInfo("THEN(b);"));
        when(chainMapper.updateById(any(DatabusChain.class))).thenReturn(1);
        when(marketItemMapper.update(isNull(), any())).thenReturn(0);

        assertNotNull(chainService.publish(1L, 1L));
        verify(marketItemMapper).update(isNull(), any());
    }

    /**
     * 发布引导闭包根链回查 stub（decisions §十四-6）：collectUnlistedClosure →
     * collectChainClosure 按 chainCode selectOne 根链，返回内存主链（THEN(b) 无子链引用，
     * 闭包＝主链自身）。只在走到引导收集的场景调用——状态 gate 前置拒绝的场景不 stub
     * （MockitoExtension 严格模式防 UnnecessaryStubbing）。
     */
    private void stubClosureRoot() {
        DatabusChain root = chain(1L, "chainB", "链B", ChainStatusEnum.DRAFT.getCode(), 1);
        when(chainMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DatabusChain> wrapper = inv.getArgument(0);
            // eq 条件值懒物化（formatParam 在取 SQL 段时才写入 paramNameValuePairs），先取段再回读
            wrapper.getSqlSegment();
            Object code = wrapper.getParamNameValuePairs().values().iterator().next();
            return "chainB".equals(code) ? root : null;
        });
    }

    private DatabusChain chain(Long id, String code, String name, String status, Integer version) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setStatus(status);
        chain.setVersion(version);
        chain.setIsTemplate("0");
        chain.setCmpProperty(then(node("b")));
        return chain;
    }

    private ELInfo elInfo(String elStr) {
        ELInfo info = new ELInfo();
        info.setElStr(elStr);
        return info;
    }

    private CmpProperty node(String id) {
        return CmpProperty.builder().id(id).type("NodeComponent").build();
    }

    private CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }
}
