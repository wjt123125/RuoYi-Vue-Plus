package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.service.IDatabusChainService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * validateClosureOnMarket 闭包市场校验单元测试（decisions §十四-4：闭包全链必须 ONLINE）。
 * <p>
 * 纯 JUnit5＋Mockito 直构 Service（冒烟上下文不含 Service 层）；被测方法为 install 内部
 * 闸门（private），经反射调用验证拒绝口径——缺项报「未上架」、非 ONLINE 报具体状态、
 * 全 ONLINE 放行。安装主链路事务级覆盖在块3 install 测试补齐。
 *
 * @author databus
 */
@Tag("dev")
class DatabusMarketClosureValidateTest {

    private DatabusMarketItemMapper marketItemMapper;

    private DatabusMarketServiceImpl service;

    /**
     * 纯 Mockito 环境无 Spring/MyBatis 装配：手动注册实体元数据，LambdaQueryWrapper 的
     * lambda 列解析才不会抛「can not find lambda cache」（TableInfoHelper 全局缓存，幂等）。
     */
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatabusMarketItem.class);
    }

    @BeforeEach
    void setUp() {
        marketItemMapper = mock(DatabusMarketItemMapper.class);
        service = new DatabusMarketServiceImpl(
            marketItemMapper,
            mock(DatabusMarketCategoryMapper.class),
            mock(DatabusInstallMapper.class),
            mock(DatabusChainMapper.class),
            mock(DatabusComponentMapper.class),
            mock(DatabusConnectorDefMapper.class),
            jsonMapper(),
            mock(IDatabusChainService.class),
            new ExpressGenerator());
    }

    /**
     * 闭包全链 ONLINE：放行（主链即被装 item 本身，入口已校验 ONLINE，此处复检不误伤）。
     */
    @Test
    void validateClosureOnMarket_allOnline_passes() {
        when(marketItemMapper.selectList(any())).thenReturn(List.of(
            item(11L, 1L, "ONLINE"),
            item(12L, 2L, "ONLINE")));

        assertDoesNotThrow(() -> invoke(closure(
            chain(1L, "a", "链A"),
            chain(2L, "b", "链B"))));
    }

    /**
     * 闭包子链无市场行（未上架）：拒装并报具体链名。
     */
    @Test
    void validateClosureOnMarket_subNotListed_rejectsWithChainName() {
        when(marketItemMapper.selectList(any())).thenReturn(List.of(
            item(11L, 1L, "ONLINE")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> invoke(closure(chain(1L, "a", "链A"), chain(2L, "b", "链B"))));

        assertEquals("依赖链路'链B'市场状态为未上架，暂不可安装", ex.getMessage());
    }

    /**
     * 闭包子链市场状态非 ONLINE（改版冻结 PENDING）：拒装并报具体状态（decisions §十四-8）。
     */
    @Test
    void validateClosureOnMarket_subPending_rejectsWithStatus() {
        when(marketItemMapper.selectList(any())).thenReturn(List.of(
            item(11L, 1L, "ONLINE"),
            item(12L, 2L, "PENDING")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> invoke(closure(chain(1L, "a", "链A"), chain(2L, "b", "链B"))));

        assertEquals("依赖链路'链B'市场状态为PENDING，暂不可安装", ex.getMessage());
    }

    /**
     * 驳回态（REJECTED）同样拒装：报具体状态。
     */
    @Test
    void validateClosureOnMarket_subRejected_rejectsWithStatus() {
        when(marketItemMapper.selectList(any())).thenReturn(List.of(
            item(11L, 1L, "ONLINE"),
            item(12L, 2L, "REJECTED")));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> invoke(closure(chain(1L, "a", "链A"), chain(2L, "b", "链B"))));

        assertEquals("依赖链路'链B'市场状态为REJECTED，暂不可安装", ex.getMessage());
    }

    // ---------------------------- 桩构造 ----------------------------

    /**
     * 反射调用私有闸门（块1 未接 install，块3 接线后由 install 测试直接覆盖主链路）。
     */
    private void invoke(LinkedHashMap<String, DatabusChain> closure) throws Throwable {
        Method method = DatabusMarketServiceImpl.class.getDeclaredMethod("validateClosureOnMarket", LinkedHashMap.class);
        method.setAccessible(true);
        try {
            method.invoke(service, closure);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private LinkedHashMap<String, DatabusChain> closure(DatabusChain... chains) {
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        for (DatabusChain chain : chains) {
            closure.put(chain.getChainCode(), chain);
        }
        return closure;
    }

    private DatabusChain chain(Long id, String code, String name) {
        DatabusChain chain = new DatabusChain();
        chain.setId(id);
        chain.setChainCode(code);
        chain.setChainName(name);
        return chain;
    }

    private DatabusMarketItem item(Long id, Long sourceId, String status) {
        DatabusMarketItem item = new DatabusMarketItem();
        item.setId(id);
        item.setResourceType("CHAIN");
        item.setSourceId(sourceId);
        item.setStatus(status);
        return item;
    }

    private ObjectMapper jsonMapper() {
        return JsonMapper.builder().build();
    }
}
