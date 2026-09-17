package org.dromara.databus.connector;

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
 * <p>本档边界：schema 用简化的字段描述（type/label/required/default），不引入 JSON Schema 全套规范。
 * 未来升级为完整 JSON Schema 时只需扩展 {@link ConfigField} 即可。
 *
 * @author databus
 */
@Data
public class ConnectorDescriptor {

    /** Connector 类型标识（与 {@link Connector#getType()} 一致），如 "bpmHttp" */
    private String type;

    /** 展示标签（连接管理页与物料展示用），如 "BPM HTTP 连接器" */
    private String label;

    /** 图标名（Iconify 字符串，与 cmp-defs icon 同范式），如 "ph:plugs" */
    private String icon;

    /** 操作清单：本连接器支持的业务操作列表（如 BPM HTTP 含 4 个操作） */
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

        /**
         * 是否敏感字段（密码 / token / secretKey 等）。
         * <p>为 true 时连接管理服务把该字段从明文 config 中拆出，写入 credentials 加密 JSON 列；
         * 未来动态表单阶段前端据此渲染密码框（type=password）。
         */
        private boolean sensitive;

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
}
