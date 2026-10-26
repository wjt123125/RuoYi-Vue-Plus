package org.dromara.databus.market.support;

/**
 * 市场模块共用的固定值：上架状态、安装模式、资源本身的状态、编码用的分隔符和长度限制。
 * <p>
 * 这些原来都是 DatabusMarketServiceImpl 里的私有常量。按职责拆成查询、安装、上架三个类之后，
 * 同一批值好几个类都要用（{@code ONLINE} 三处都要判，{@code __} 和 {@code /} 安装和上架两边都要拼），
 * 所以提到这里存一份，各个类再按需要取个别名，省得同一个值到处各写一遍、改的时候漏掉。
 * <p>
 * 资源类型（CHAIN / COMPONENT / CONNECTOR）放在 {@link MarketResourceTypes} 里，这里不重复。
 *
 * @author databus
 * @since 2026-10-26
 */
public final class MarketStates {

    /**
     * 上架状态：待审
     */
    public static final String STATUS_PENDING = "PENDING";

    /**
     * 上架状态：上线
     */
    public static final String STATUS_ONLINE = "ONLINE";

    /**
     * 上架状态：下线
     */
    public static final String STATUS_OFFLINE = "OFFLINE";

    /**
     * 上架状态：驳回
     */
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * 安装模式：复制一份副本（只有装链路用）
     */
    public static final String MODE_FORK = "FORK";

    /**
     * 安装模式：大家共用同一个（只有装组件用：不复制副本，localResourceId 直接指向源组件，
     * 作者改了或者下架了，安装者那边马上跟着变）
     */
    public static final String MODE_SHARED = "SHARED";

    /**
     * 链路状态（databus_chain.status）：草稿
     */
    public static final String CHAIN_STATUS_DRAFT = "0";

    /**
     * 链路状态（databus_chain.status）：已发布
     */
    public static final String CHAIN_STATUS_PUBLISHED = "1";

    /**
     * 组件启停（databus_component.status）：启用
     */
    public static final String COMPONENT_STATUS_ENABLED = "0";

    /**
     * 连接器 def 行的状态（databus_connector_def.status，和 DatabusConnectorServiceImpl 里的值一样）：启用。
     * 判断「装了没有」时用（装连接器＝改成启用，全局生效，不是记在某个人的安装记录里）
     */
    public static final String DEF_STATUS_ENABLED = "ENABLED";

    /**
     * 模板标记（0＝不是）：复制出来的副本一律不带模板标记
     */
    public static final String TEMPLATE_FLAG_NO = "0";

    /**
     * 模板标记（1＝是）：被标成精选模板的副本，安装时不能拿来复用
     */
    public static final String TEMPLATE_FLAG_YES = "1";

    /**
     * 编码前缀专用的分隔符（源编码里不许出现这两个字符）
     */
    public static final String CODE_SEPARATOR = "__";

    /**
     * 拼「按内容算出的那个标识值」时，各段之间用的分隔符
     */
    public static final String HASH_SEP = "|";

    /**
     * 展示用的编码里，「谁的」和「编码」中间的分隔符
     */
    public static final String DISPLAY_SEPARATOR = "/";

    /**
     * 编码最大长度（和 DatabusChainBo 上的 @Size 一样；组件安装已经改成大家共用同一个，
     * 不再生成副本编码了）
     */
    public static final int CHAIN_CODE_MAX_LEN = 100;

    /**
     * 新建草稿的初始版本号（和 DatabusChainServiceImpl 里的值一样）
     */
    public static final int DRAFT_VERSION = 1;

    /**
     * 副本编码后缀序号的最大值（防一下万一，正常到不了）
     */
    public static final int CODE_SEQ_MAX = 9999;

    /**
     * 安装结果里「这一条是怎么来的」：这次新复制的
     * （decisions §十四-1：依赖清单里每条链都单独复制成一行）
     */
    public static final String CASCADE_NEW = "NEW";

    /**
     * 安装结果里「这一条是怎么来的」：复用了我名下已有的一条副本
     * （decisions §十四-2：编码撞了就直接用现有那条，前端提示「使用你现有副本」）
     */
    public static final String CASCADE_REUSED = "REUSED";

    private MarketStates() {
    }
}
