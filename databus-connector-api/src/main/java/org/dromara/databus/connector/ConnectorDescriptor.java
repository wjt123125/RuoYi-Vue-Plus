package org.dromara.databus.connector;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器元数据：描述一个 Connector 类型的能力清单与配置 schema。
 *
 * <p>驱动两个场景：
 * <ul>
 *   <li>前端 cmp-defs 物料自动生成：{@link #operations} 列出每个操作的注册名、标签、参数 schema、
 *       产出数据字段，前端据此生成组件物料定义</li>
 *   <li>连接管理页表单动态渲染：{@link #configSchema} 描述 Connection.config 各字段的类型/标签/必填/默认值，
 *       前端按 schema 动态渲染表单，不写死字段</li>
 * </ul>
 *
 * @author databus
 */
@Data
public class ConnectorDescriptor {

    /** Connector 类型标识（与 {@link Connector#getType()} 一致），如 "jdbc-postgresql" */
    private String type;

    /** 展示标签（连接管理页与物料展示用），如 "PostgreSQL JDBC 连接器" */
    private String label;

    /** 图标名（Iconify 字符串，与 cmp-defs icon 同范式），如 "ph:plugs" */
    private String icon;

    /**
     * 连接器族（方案 §8.3）：组件按族匹配连接器，而不是按具体 type。
     *
     * <p>JDBC 各库填 {@code "jdbc"}，BPM 填 {@code "bpmHttp"}（该族只有一个成员，族名即 type）。
     * 这样 {@code dbQuery} 一个组件就能服务
     * jdbc-postgresql / jdbc-mysql / jdbc-oracle …，新增一个库不用新增组件、不用改前端物料；
     * 连接管理页的连接器下拉也按族过滤（只装了 pg 包时，下拉里不会出现 Oracle）。
     * 留空表示不属于任何族（组件不按族匹配）。
     */
    private String family;

    /** 操作清单：本连接器支持的业务操作列表 */
    private List<OperationDef> operations = new ArrayList<>();

    /** Connection 配置 schema：字段名 → 字段定义，驱动连接管理页表单动态渲染 */
    private Map<String, ConfigField> configSchema = new LinkedHashMap<>();

    /**
     * 连接表单的分组元数据：列表顺序即 tab 顺序，主组恒第一个 tab。
     *
     * <p>留空表示连接器不分 tab，前端退化为「按字段 group 首次出现顺序」自动分组。
     * 平台侧（连接名称 / 连接器类型 / 启用 / 连接ID）与备注一律不进这份声明。
     */
    private List<ConfigGroup> groups = new ArrayList<>();

    /**
     * 表单分组定义（一个组＝一个 tab）。
     */
    @Data
    public static class ConfigGroup {

        /** 分组键（与 {@link ConfigField#getGroup()} 对应） */
        private String key;

        /** tab 标题；缺省取 key */
        private String label;

        /** 主组：渲染为第一个 tab */
        private boolean primary;

        /**
         * 组内栅格列数（正整数，缺省 2）。
         *
         * <p>这是组内布局的<b>总份数</b>，字段用 {@link ConfigField#getSpan()} 声明自己占几份：
         * columns=1 即单列（field.span 无意义），columns=2 是常规双列，
         * columns=4 才能表达「主机 3 份 + 端口 1 份」这类一行内不等宽的搭配。
         * 前端据此生成 {@code repeat(columns, minmax(0, 1fr))}，不写死任何具体字段。
         */
        private Integer columns;

        /** 主组（常规）：单列 */
        public static ConfigGroup primary(String key, String label) {
            ConfigGroup group = new ConfigGroup();
            group.setKey(key);
            group.setLabel(label);
            group.setPrimary(true);
            group.setColumns(1);
            return group;
        }

        /** 普通组：双列 */
        public static ConfigGroup of(String key, String label) {
            ConfigGroup group = new ConfigGroup();
            group.setKey(key);
            group.setLabel(label);
            group.setColumns(2);
            return group;
        }

        /** 覆盖组内栅格份数（配合 field.span 表达不等宽搭配） */
        public ConfigGroup columns(int columns) {
            this.columns = columns;
            return this;
        }
    }

    /**
     * 操作定义：一个业务操作的元数据。
     */
    @Data
    public static class OperationDef {

        /** 操作名（与 Connector 业务方法名对应），如 "createSession" */
        private String name;

        /** 展示标签，如 "创建会话" */
        private String label;

        /** LiteflowComponent 注册名（与 cmp-defs id 一致），如 "sessionCreate" */
        private String componentId;

        /** 操作的入参字段清单（key=字段名，value=字段定义） */
        private Map<String, ConfigField> params = new LinkedHashMap<>();

        /** 操作产出字段清单（写入数据空间 $.&lt;tag&gt;.* 的字段名列表） */
        private List<String> outputs = new ArrayList<>();
    }

    /**
     * 配置字段定义：Connection.config 或 Operation.params 中单个字段的元数据。
     */
    @Data
    public static class ConfigField {

        /** 字段类型：string / int / long / boolean / double / array / object */
        private String type;

        /** 展示标签 */
        private String label;

        /** 是否必填 */
        private boolean required;

        /** 默认值（type 为 string 时为 String，int 时为 Integer，array 时为 List，等） */
        private Object defaultValue;

        /** 描述说明（连接管理页表单下方提示） */
        private String description;

        /** 是否敏感字段（密码 / token / secretKey 等），前端据此渲染密码框、平台拆出加密落 credentials */
        private boolean sensitive;

        /** 控件类型：TEXT / TEXTAREA / NUMBER / BOOLEAN / SELECT / PASSWORD / KEY_VALUE_MAP */
        private String widget;

        /** SELECT 候选项（widget=SELECT 时前端下拉选项） */
        private List<Option> options;

        /** 占位提示（输入框 placeholder） */
        private String placeholder;

        /** 条件显示规则（满足才显示该字段） */
        private ShowWhen showWhen;

        /** 分组键（对应 {@link ConnectorDescriptor#groups} 里的 key；留空归 default 组） */
        private String group;

        /**
         * 组内栅格占位份数（1..所属分组 columns；组只有 1 列时无意义）。
         *
         * <p>以分组 columns 为总份数：columns=2 时 1=半列、2=整列；
         * columns=4 时可用 3+1 表达「宽字段：窄字段」这种不等宽同行。
         */
        private int span = 1;

        /**
         * 数值单位后缀（如 "毫秒" / "秒" / "行"）。
         *
         * <p>只影响展示：前端把它渲染在数值输入框右侧（{@code #append}），
         * 标签里就不必再写「（毫秒）」这类单位后缀。留空表示无单位。
         */
        private String unit;

        /**
         * 说明呈现方式：INLINE=常显在字段下方；留空=收进 ⓘ 提示（description 作为提示内容）。
         * 面向「填的时候要反复看」的短提示才声明 INLINE，长解释默认进提示。
         */
        private String help;

        public ConfigField sensitive() {
            this.sensitive = true;
            return this;
        }

        public ConfigField inGroup(String group) {
            this.group = group;
            return this;
        }

        public ConfigField description(String description) {
            this.description = description;
            return this;
        }

        public ConfigField span(int span) {
            this.span = span;
            return this;
        }

        public ConfigField unit(String unit) {
            this.unit = unit;
            return this;
        }

        public ConfigField help(String help) {
            this.help = help;
            return this;
        }

        public static ConfigField ofString(String label, boolean required, String defaultValue) {
            ConfigField f = new ConfigField();
            f.setType("string");
            f.setLabel(label);
            f.setRequired(required);
            f.setDefaultValue(defaultValue);
            return f;
        }

        public static ConfigField ofInt(String label, boolean required, Integer defaultValue) {
            ConfigField f = new ConfigField();
            f.setType("int");
            f.setLabel(label);
            f.setRequired(required);
            f.setDefaultValue(defaultValue);
            return f;
        }

        public static ConfigField ofArray(String label, boolean required, Object defaultValue) {
            ConfigField f = new ConfigField();
            f.setType("array");
            f.setLabel(label);
            f.setRequired(required);
            f.setDefaultValue(defaultValue);
            return f;
        }
    }

    /**
     * select 候选项（与平台侧 OptionVo 字段逐字对齐）。
     *
     * @param label 展示文案
     * @param value 提交值
     */
    public record Option(String label, String value) {
    }

    /**
     * 字段条件显示规则（与平台侧 ShowWhenVo 字段逐字对齐）：同一字段多条规则为 AND。
     *
     * @param field 依据字段名（相对当前对象层级，支持点路径）
     * @param eq    相等条件，null 表示不启用
     * @param ne    不等条件，null 表示不启用
     * @param in    属于集合，null 表示不启用
     * @param notIn 不属于集合，null 表示不启用
     * @param empty 空值条件：true=依据字段为空才显示，false=依据字段已填才显示，null 表示不启用
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShowWhen(String field,
                           String eq,
                           String ne,
                           List<String> in,
                           List<String> notIn,
                           Boolean empty) {

        public ShowWhen {
            if (in != null) {
                in = List.copyOf(in);
            }
            if (notIn != null) {
                notIn = List.copyOf(notIn);
            }
        }

        /** 依据字段为空（null / 空串 / 空数组 / 空对象）时才显示本字段 */
        public static ShowWhen visibleWhenBlank(String field) {
            return new ShowWhen(field, null, null, null, null, true);
        }

        /** 依据字段已填时才显示本字段 */
        public static ShowWhen visibleWhenFilled(String field) {
            return new ShowWhen(field, null, null, null, null, false);
        }
    }
}