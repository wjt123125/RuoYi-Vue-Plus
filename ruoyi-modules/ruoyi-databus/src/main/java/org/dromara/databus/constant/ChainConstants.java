package org.dromara.databus.constant;

/**
 * 链路域常量：草稿版本、名称/编码长度、复制序号、引用分隔符、执行深度与试跑标识。
 * <p>
 * 模板标记（is_template）与草稿状态（DRAFT）跨链路、组件、市场共用，统一放 {@link CommonConstants}。
 *
 * @author databus
 */
public final class ChainConstants {

    private ChainConstants() {
    }

    /**
     * 新建链路的初始版本号
     */
    public static final int DRAFT_VERSION = 1;

    /**
     * 模板列表默认排序值
     */
    public static final int TEMPLATE_DEFAULT_SORT = 0;

    /**
     * 链路名称长度上限
     */
    public static final int CHAIN_NAME_MAX_LEN = 100;

    /**
     * 链路编码长度上限（与市场发布共用同一约束）
     */
    public static final int CHAIN_CODE_MAX_LEN = 100;

    /**
     * 复制链路时追加的名称后缀
     */
    public static final String COPY_NAME_SUFFIX = "副本";

    /**
     * 复制链路时的编码序号起点
     */
    public static final int COPY_CODE_SEQ_START = 2;

    /**
     * 复制链路时的编码序号上限
     */
    public static final int COPY_CODE_SEQ_MAX = 9999;

    /**
     * 被引用资源的类型：链路
     */
    public static final String CHAIN_REF_TYPE = "CHAIN";

    /**
     * 跨链路引用路径的分隔符
     */
    public static final String REF_PATH_SEPARATOR = " → ";

    /**
     * 单条链路的最大嵌套调用深度
     */
    public static final int MAX_DEPTH = 10;

    /**
     * 画布循环节点的最大迭代层数
     */
    public static final int MAX_LOOP_DEPTH = 3;

    /**
     * 循环深度探测的防御性上限：真实嵌套超过该值视为探测异常（业务上限是 {@link #MAX_LOOP_DEPTH}）
     */
    public static final int PROBE_HARD_LIMIT = 32;

    /**
     * 完整日志级别
     */
    public static final String LOG_LEVEL_FULL = "FULL";

    /**
     * 预览试跑用的伪链路 id（不落库）
     */
    public static final String PREVIEW_CHAIN_ID = "preview-el";
}
