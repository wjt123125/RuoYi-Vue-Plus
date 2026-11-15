package org.dromara.databus.constant;

/**
 * 市场域常量：发布状态、安装模式、资源类型、编码分隔符与级联结果。
 * <p>
 * 取代原先散在 {@code market/support} 下的 MarketStates 与 MarketResourceTypes 两个类。
 *
 * @author databus
 */
public final class MarketConstants {

    private MarketConstants() {
    }

    /* ---------------- 发布状态 ---------------- */

    public static final String STATUS_PENDING = "PENDING";

    public static final String STATUS_ONLINE = "ONLINE";

    public static final String STATUS_OFFLINE = "OFFLINE";

    public static final String STATUS_REJECTED = "REJECTED";

    /* ---------------- 安装模式 ---------------- */

    /**
     * 安装模式：复制一份（FORK）
     */
    public static final String MODE_FORK = "FORK";

    /**
     * 安装模式：共享引用（SHARED）
     */
    public static final String MODE_SHARED = "SHARED";

    /* ---------------- 资源类型 ---------------- */

    public static final String TYPE_CHAIN = "CHAIN";

    public static final String TYPE_COMPONENT = "COMPONENT";

    public static final String TYPE_CONNECTOR = "CONNECTOR";

    /* ---------------- 编码规则 ---------------- */

    /**
     * 安装后编码的分隔符
     */
    public static final String CODE_SEPARATOR = "__";

    /**
     * 幂等哈希的分隔符
     */
    public static final String HASH_SEP = "|";

    /**
     * 展示名的分隔符
     */
    public static final String DISPLAY_SEPARATOR = "/";

    /**
     * 市场版本起始号
     */
    public static final int DRAFT_VERSION = 1;

    /**
     * 编码序号上限
     */
    public static final int CODE_SEQ_MAX = 9999;

    /* ---------------- 级联安装结果 ---------------- */

    /**
     * 级联安装：新建
     */
    public static final String CASCADE_NEW = "NEW";

    /**
     * 级联安装：复用已有
     */
    public static final String CASCADE_REUSED = "REUSED";

    /* ---------------- 其他 ---------------- */

    /**
     * 定义体中标识类型的字段名
     */
    public static final String DEF_TYPE_FIELD = "defType";
}
