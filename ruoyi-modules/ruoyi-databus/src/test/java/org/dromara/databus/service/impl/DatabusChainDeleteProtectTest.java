package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 块4 删除引用保护单测（decisions §十四-10）：deleteByIds 删前 LIKE 粗筛＋collectChainRefs 精确核对。
 * <p>
 * 四场景：被外部引用拒绝／无引用放行／同批闭包互引放行／LIKE 命中但非 CHAIN 引用（假阳性）放行。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DatabusChainDeleteProtectTest {

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
        // 纯 Mockito 环境下 LambdaQueryWrapper 解析列名依赖实体 TableInfo 缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusChain.class);
    }

    @BeforeEach
    void setUp() {
        chainService = new DatabusChainServiceImpl(chainMapper, expressGenerator, rulePublishService,
            marketItemMapper, componentMapper);
    }

    /**
     * 场景1：删 B，外部链 X 树里 CHAIN 引用 chainB → 拒绝＋回显「链X(chainX)」＋不执行删除
     */
    @Test
    void delete_referencedByOtherChain_rejects() {
        when(chainMapper.selectByIds(List.of(2L))).thenReturn(List.of(chain(2L, "chainB", "链B", then(node("b")))));
        when(chainMapper.selectList(any())).thenReturn(List.of(
            chain(1L, "chainX", "链X", then(node("x"), chainRef("chainB")))
        ));

        ServiceException ex = assertThrows(ServiceException.class, () -> chainService.deleteByIds(List.of(2L)));
        assertTrue(ex.getMessage().contains("所选链路仍被其他链路引用，无法删除：链X(chainX)"),
            "应回显引用链名，实际：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("请先在对应链路中移除引用"));
        verify(chainMapper, never()).deleteByIds(any());
        verify(rulePublishService, never()).removeChainQuietly(any());
    }

    /**
     * 场景2：删 B，粗筛无命中 → 放行＋删除＋清理 Rule-DB 残留
     */
    @Test
    void delete_noReference_deletesAndCleansRule() {
        when(chainMapper.selectByIds(List.of(2L))).thenReturn(List.of(chain(2L, "chainB", "链B", then(node("b")))));
        when(chainMapper.selectList(any())).thenReturn(List.of());
        when(chainMapper.deleteByIds(List.of(2L))).thenReturn(1);

        assertTrue(chainService.deleteByIds(List.of(2L)));
        verify(chainMapper).deleteByIds(List.of(2L));
        verify(rulePublishService).removeChainQuietly("chainB");
    }

    /**
     * 场景3：同批删 A+B，A 树里 CHAIN 引用 chainB（同批闭包互引）→ 允许删，两条 Rule-DB 残留都清
     */
    @Test
    void delete_sameBatchMutualReference_allows() {
        when(chainMapper.selectByIds(List.of(1L, 2L))).thenReturn(List.of(
            chain(1L, "chainA", "链A", then(chainRef("chainB"))),
            chain(2L, "chainB", "链B", then(node("b")))
        ));
        // 粗筛命中 A 自身（树里含 "chainB" 字符串）——同批豁免不算外部引用
        when(chainMapper.selectList(any())).thenReturn(List.of(
            chain(1L, "chainA", "链A", then(chainRef("chainB")))
        ));
        when(chainMapper.deleteByIds(List.of(1L, 2L))).thenReturn(2);

        assertTrue(chainService.deleteByIds(List.of(1L, 2L)));
        verify(chainMapper).deleteByIds(List.of(1L, 2L));
        verify(rulePublishService).removeChainQuietly("chainA");
        verify(rulePublishService).removeChainQuietly("chainB");
    }

    /**
     * 场景4：删 B，X 树里 node("chainB") 是普通组件 id（type 非 CHAIN）——LIKE 带引号命中
     * 但精确核对无 CHAIN 引用 → 假阳性过滤放行
     */
    @Test
    void delete_likeHitButNotChainRef_allows() {
        when(chainMapper.selectByIds(List.of(2L))).thenReturn(List.of(chain(2L, "chainB", "链B", then(node("b")))));
        when(chainMapper.selectList(any())).thenReturn(List.of(
            chain(1L, "chainX", "链X", then(node("chainB")))
        ));
        when(chainMapper.deleteByIds(List.of(2L))).thenReturn(1);

        assertTrue(chainService.deleteByIds(List.of(2L)));
        verify(chainMapper).deleteByIds(List.of(2L));
        verify(rulePublishService).removeChainQuietly("chainB");
    }

    private DatabusChain chain(Long id, String code, String name, CmpProperty tree) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        chain.setCmpProperty(tree);
        return chain;
    }

    private CmpProperty node(String id) {
        return CmpProperty.builder().id(id).type("NodeComponent").build();
    }

    private CmpProperty chainRef(String id) {
        return CmpProperty.builder().id(id).type("CHAIN").build();
    }

    private CmpProperty then(CmpProperty... children) {
        return CmpProperty.builder().type("THEN").children(List.of(children)).build();
    }
}
