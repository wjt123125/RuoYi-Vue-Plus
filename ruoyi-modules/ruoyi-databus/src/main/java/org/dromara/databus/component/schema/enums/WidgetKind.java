package org.dromara.databus.component.schema.enums;

/**
 * 配置字段输入控件类型。
 * <p>
 * AUTO 仅用于注解缺省值，反射阶段按 Java 类型解析为最终控件，schema 输出不出现 AUTO。
 *
 * @author databus
 */
public enum WidgetKind {

    /** 注解缺省值：反射时按 Java 类型推断，不输出到 schema。 */
    AUTO,

    /** 单行文本。 */
    TEXT,

    /** 多行文本（script/sql 等）。 */
    TEXTAREA,

    /** 数字（Integer/Long/Double/BigDecimal 等）。 */
    NUMBER,

    /** 布尔开关。 */
    BOOLEAN,

    /** 固定候选单选；options 为空时前端禁用。 */
    SELECT,

    /** 多选；options 为空时前端渲染自由输入标签串（List&lt;String&gt; 默认推断）。 */
    MULTISELECT,

    /** 密码/密钥掩码输入。 */
    PASSWORD,

    /**
     * 领域控件：连接选择（12 个 BPM/rds 件的 connectionId）。
     * <p>
     * 实施补全（2026-10-02 推广批）：设计文档 §3.2 的 11 控件之外按 §6.1「领域控件 ConnectionSelect」
     * 补齐触发契约——值仍为 connectionId 字符串（业务键），前端拉 /databus/connection/options 渲染下拉，
     * 纯增量枚举，旧 schema 不出现该值。
     */
    CONNECTION_SELECT,

    /** Map 键值对行编辑（fields 用 keyWidget/valueWidget 描述）。 */
    KEY_VALUE_MAP,

    /** 对象数组行编辑（List&lt;Bean&gt;，列由元素 Bean 字段递归得到）。 */
    OBJECT_ROWS,

    /**
     * 嵌套对象分组（单个 Bean 字段，折叠分组内递归子字段）。
     * <p>
     * 实施补全：设计文档 §3.4/§6.2 描述了「嵌套 Bean 折叠 object 分组」，
     * 但 §3.2 控件枚举漏列，此处补齐，与 OBJECT_ROWS（数组行编辑）区分。
     */
    OBJECT,

    /** JSON 逃生舱：任意值/无法推断的类型。 */
    JSON
}
