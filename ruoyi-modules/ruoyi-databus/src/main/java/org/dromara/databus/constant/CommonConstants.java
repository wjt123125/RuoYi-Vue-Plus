package org.dromara.databus.constant;

/**
 * 跨域公共常量：到处复用的标记位、目录树约束，以及指标、推荐、收藏等小域的默认值与上限。
 * <p>
 * 只收纳「多个域共享同一个字面量」或「单域但只有两三个值」的常量；某个域自成体系的，放各自的
 * {@code XxxConstants}（如 {@link AgentConstants}、{@link ChainConstants}）。
 *
 * @author databus
 */
public final class CommonConstants {

    private CommonConstants() {
    }

    /* ---------------- 通用标记位 ---------------- */

    /**
     * 启用 / 是（sys_databus_connection.enabled、databus_component.is_script 等 Y/N 列）
     */
    public static final String FLAG_YES = "Y";

    /**
     * 停用 / 否（与 {@link #FLAG_YES} 对称的取值，Y/N 列的另一半）
     */
    public static final String FLAG_NO = "N";

    /**
     * 模板标记：否（is_template 列）
     */
    public static final String TEMPLATE_FLAG_NO = "0";

    /**
     * 模板标记：是（is_template 列）
     */
    public static final String TEMPLATE_FLAG_YES = "1";

    /**
     * 草稿状态（链路与组件共用同一套 status 枚举字面量）
     */
    public static final String STATUS_DRAFT = "DRAFT";

    /**
     * 系统内置归属（owner_type 列）
     */
    public static final String OWNER_SYSTEM = "SYSTEM";

    /* ---------------- 目录树 ---------------- */

    /**
     * 根目录的 parent_id
     */
    public static final Long ROOT_PARENT = 0L;

    /**
     * 目录最大嵌套层级
     */
    public static final int DIRECTORY_MAX_DEPTH = 100;

    /**
     * 目录名称长度上限
     */
    public static final int DIRECTORY_NAME_MAX_LEN = 100;

    /* ---------------- 收藏 ---------------- */

    public static final String FAVORITE_TYPE_CHAIN = "chain";

    public static final String FAVORITE_TYPE_COMPONENT = "component";

    /* ---------------- 监控指标 ---------------- */

    public static final int METRIC_DEFAULT_HOURS = 24;

    public static final int METRIC_MAX_HOURS = 720;

    public static final int METRIC_DEFAULT_TOP_N = 10;

    public static final int METRIC_MAX_TOP_N = 50;

    public static final int METRIC_DEFAULT_STREAM_LIMIT = 20;

    public static final int METRIC_MAX_STREAM_LIMIT = 100;

    /* ---------------- 组件推荐打分 ---------------- */

    /**
     * 命中任意锚点（不限定位置）
     */
    public static final String RECOMMEND_ANY_ANCHOR = "ANY";

    public static final int RECOMMEND_DEFAULT_SEED_SCORE = 40;

    public static final int RECOMMEND_USAGE_BOOST_CAP = 60;

    public static final int RECOMMEND_FINAL_SCORE_CAP = 100;
}
