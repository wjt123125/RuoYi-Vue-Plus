package org.dromara.databus.constant;

/**
 * 组件域常量：脚本语言、废弃标记与文案、AI 显式探针前缀、默认分类、schema 版本与脚本载荷字段名。
 *
 * @author databus
 */
public final class ComponentConstants {

    private ComponentConstants() {
    }

    /**
     * 是否废弃：否
     */
    public static final String DEPRECATED_NO = "0";

    /**
     * 是否废弃：是
     */
    public static final String DEPRECATED_YES = "1";

    /**
     * 原作者删除组件时写入的废弃说明
     */
    public static final String DEPRECATE_NOTE_RETIRED = "原作者已删除该组件，存量链路仍可运行，请尽快替换";

    /**
     * 脚本语言：Java
     */
    public static final String SCRIPT_LANG_JAVA = "java";

    /**
     * AI 试跑用的临时节点 id 前缀
     */
    public static final String PROBE_NODE_PREFIX = "__agent_probe_";

    /**
     * AI 试跑用的临时链路 id 前缀
     */
    public static final String PROBE_CHAIN_PREFIX = "__agent_probe_chain_";

    /**
     * 未归类组件的默认分类
     */
    public static final String CATEGORY_DEFAULT = "PLATFORM";

    /**
     * 物料选项 schema 当前版本
     */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * 可用于插槽的字段标记
     */
    public static final String DOMAIN_SLOT = "slot";

    /**
     * 脚本草稿载荷字段：组件元信息
     */
    public static final String FIELD_META = "meta";

    /**
     * 脚本草稿载荷字段：参数 schema
     */
    public static final String FIELD_SCHEMA = "schema";

    /**
     * 脚本草稿载荷字段：源码
     */
    public static final String FIELD_SCRIPT = "script";
}
