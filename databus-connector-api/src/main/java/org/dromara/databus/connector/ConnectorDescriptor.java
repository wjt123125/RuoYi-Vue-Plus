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

        /** 分组（"基础" / "高级"，连接管理页表单折叠分组用） */
        private String group;

        public ConfigField sensitive() {
            this.sensitive = true;
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
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShowWhen(String field,
                           String eq,
                           String ne,
                           List<String> in,
                           List<String> notIn) {

        public ShowWhen {
            if (in != null) {
                in = List.copyOf(in);
            }
            if (notIn != null) {
                notIn = List.copyOf(notIn);
            }
        }
    }
}