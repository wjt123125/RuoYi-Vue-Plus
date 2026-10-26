package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 查依赖清单（装成 {@link MarketClosure}）。
 * <p>
 * 调一次 {@link #load(String)} 查三次库：市场上的链行、组件行、市场上的组件行
 * （组件编码一个都没有时，后两次就不查了）。收集子链这件事还是交给
 * {@link IDatabusChainService#collectChainClosure(String)} 去做——
 * 链之间有环、子链查不到、子链没发布这些不许过的情况都由它拦，本类不再判一遍。
 * <p>
 * 三次查市场行都可能出现同一条源行对上多条结果，这时一律取第一条：
 * 上架那边已经保证「同一条源链在市场里最多只有一行」（重复提交是更新，不是新增），
 * 这里合并只是防一下意外。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketClosureLoader {

    private final IDatabusChainService chainService;

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusComponentMapper componentMapper;

    /**
     * 按根链编码把完整依赖清单查出来。
     *
     * @param rootChainCode 根链编码（安装时传市场源链，审核时传正在审的物料指向的那条链）
     * @return 只读的依赖清单；收集子链时不允许过的情况都以 ServiceException 抛出来
     */
    public MarketClosure load(String rootChainCode) {
        Map<String, DatabusChain> chains = chainService.collectChainClosure(rootChainCode);
        List<String> componentCodes = collectComponentCodes(chains.values());
        Map<Long, DatabusMarketItem> chainItems = queryMarketItems(MarketResourceTypes.CHAIN,
            chains.values().stream().map(DatabusChain::getId).toList());
        Map<String, DatabusComponent> components = componentCodes.isEmpty() ? Map.of()
            // 这里用 LinkedHashMap：上架时顺带补审核单那一步会直接遍历 components()，
            // 所以要保住 selectList 查出来的原顺序，和把这段代码抽出来之前的行为一样
            : componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
                    .in(DatabusComponent::getComponentCode, componentCodes))
                .stream()
                .collect(Collectors.toMap(DatabusComponent::getComponentCode, c -> c, (a, b) -> a,
                    LinkedHashMap::new));
        Map<Long, DatabusMarketItem> componentItems = queryMarketItems(MarketResourceTypes.COMPONENT,
            components.values().stream().map(DatabusComponent::getId).toList());
        return new MarketClosure(chains, componentCodes, chainItems, components, componentItems);
    }

    /**
     * 把这批链引用的组件编码都收集出来，去过重，按第一次出现的顺序排。
     */
    private List<String> collectComponentCodes(Collection<DatabusChain> chains) {
        List<String> codes = new ArrayList<>();
        for (DatabusChain chain : chains) {
            for (String code : ChainForkRedirector.collectComponentCodes(chain.getCmpProperty())) {
                if (!codes.contains(code)) {
                    codes.add(code);
                }
            }
        }
        return codes;
    }

    /**
     * 按资源类型一次查一批市场行，返回「源 id → 市场行」。
     */
    private Map<Long, DatabusMarketItem> queryMarketItems(String resourceType, List<Long> sourceIds) {
        if (sourceIds.isEmpty()) {
            return Map.of();
        }
        return marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .eq(DatabusMarketItem::getResourceType, resourceType)
                .in(DatabusMarketItem::getSourceId, sourceIds))
            .stream()
            .collect(Collectors.toMap(DatabusMarketItem::getSourceId, item -> item, (a, b) -> a));
    }
}
