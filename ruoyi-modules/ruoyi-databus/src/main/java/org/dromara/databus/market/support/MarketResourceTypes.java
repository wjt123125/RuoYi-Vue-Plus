package org.dromara.databus.market.support;

/**
 * 市场物料行的资源类型（就是 {@code databus_market_item.resource_type} 这一列的取值）。
 * <p>
 * 原来 DatabusMarketServiceImpl 和 DatabusChainServiceImpl 各自都私有一份、写了两遍；
 * 查依赖清单的 {@link MarketClosureLoader} 也要按资源类型去查市场行，所以提到这里共用一份。
 * <p>
 * 注意别和「组件树里 CHAIN 节点的 type」搞混（ChainForkRedirector.CHAIN_TYPE、
 * DatabusChainServiceImpl.CHAIN_REF_TYPE）：那个是判断 cmpProperty 节点是什么用的字段，
 * 只是取值刚好也写成 {@code CHAIN}，说的不是一回事，不归本类管。
 *
 * @author databus
 * @since 2026-10-26
 */
public final class MarketResourceTypes {

    /**
     * 链路
     */
    public static final String CHAIN = "CHAIN";

    /**
     * 组件
     */
    public static final String COMPONENT = "COMPONENT";

    /**
     * 连接器（§15.3「什么都能装」：装连接器＝把 def 行改成启用状态，
     * 不复制副本、也不写安装记录）
     */
    public static final String CONNECTOR = "CONNECTOR";

    private MarketResourceTypes() {
    }
}
