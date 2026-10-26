package org.dromara.databus.market.support;

import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一条链的完整依赖清单（只读），由 {@link MarketClosureLoader#load(String)} 一次查齐。
 * <p>
 * 里面装五样东西：主链和它引用的全部子链（子链再引用的也算）、这些链用到的全部组件编码、
 * 以及这些链和组件各自在市场上的行。安装、上架时顺带补审核单、通过审核前查依赖、审核详情这四件事
 * 用的都是同一份清单。以前每个地方各查各的，那些不一样的地方（比如审核时要把主链自己从依赖里排掉）
 * 藏在几乎一样的代码里，改一处很容易漏掉另一处。现在查的活都收到 loader 里，
 * 这种不一样的地方就只写在使用它的那一个方法里。
 * <p>
 * 每个集合都是不可变的，调用方只能读、不能改。
 *
 * @param chains         链编码 → 链行。遍历顺序就是 {@code collectChainClosure} 给的顺序：
 *                       被引用的在前面、主链在最后（安装就照这个顺序一条条复制，
 *                       这样改引用时子链的新编码已经知道了）
 * @param componentCodes 所有链引用的组件编码合在一起，去过重，按第一次出现的顺序排
 * @param chainItems     源链 id → 市场行。链没上架过就查不到，调用方按「未上架」处理
 * @param components     组件编码 → 组件行。编码在 {@link #componentCodes} 里有、但这里查不到，
 *                       说明组件行已经被删了。遍历顺序就是数据库查出来的顺序
 *                       （上架时顺带补审核单那一步直接遍历它）
 * @param componentItems 源组件 id → 市场行
 * @author databus
 * @since 2026-10-26
 */
public record MarketClosure(
    Map<String, DatabusChain> chains,
    List<String> componentCodes,
    Map<Long, DatabusMarketItem> chainItems,
    Map<String, DatabusComponent> components,
    Map<Long, DatabusMarketItem> componentItems) {

    public MarketClosure {
        chains = Collections.unmodifiableMap(chains);
        componentCodes = List.copyOf(componentCodes);
        chainItems = Collections.unmodifiableMap(chainItems);
        components = Collections.unmodifiableMap(components);
        componentItems = Collections.unmodifiableMap(componentItems);
    }
}
