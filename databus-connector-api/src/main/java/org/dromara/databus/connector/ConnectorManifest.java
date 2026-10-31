package org.dromara.databus.connector;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接器包 manifest.json 解析模型（万物皆包）。
 *
 * <p>zip 包根级 manifest.json 的 Jackson 直映对象：字段对齐既有
 * {@link ConnectorDescriptor}（type / label / icon / operations / configSchema），
 * 新增 {@code version} 与 {@code engine} 两个包级字段。
 *
 * <p>用途：上传链路（POST /databus/connector/packages）解析校验；defs 接口回填
 * Vo 的 label / icon / operations / configSchema（前端直渲动态表单）。
 *
 * @author databus
 */
@Data
public class ConnectorManifest {

    /** type 命名规则：字母开头，字母/数字/下划线/中划线，≤64 位（防路径注入 package_path） */
    private static final Pattern TYPE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$");

    /** version 命名规则：字母/数字开头，字母/数字/点/下划线/中划线，≤32 位（防路径注入 package_path） */
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}$");

    /** 引擎档：http（声明式，换 JSON 即生效）/ jvm（PluginClassLoader 装卸）/ native（卸载须重启） */
    private static final List<String> ENGINES = List.of("http", "jvm", "native");

    /** Connector 类型标识（全局唯一），如 "jdbc-postgresql" */
    private String type;

    /** 展示标签，如 "PostgreSQL JDBC 连接器" */
    private String label;

    /** 图标名（Iconify 字符串），如 "ph:plugs" */
    private String icon;

    /** 版本号（同 type 升版本＝热更新 def 行） */
    private String version;

    /** 引擎档（http / jvm / native） */
    private String engine;

    /** 主类：engine=jvm 时反射实例化的 Connector 实现类全限定名（包路径连字符换点） */
    private String mainClass;

    /** 数据库类型：engine=jvm 且为 JDBC 类连接器时的 dbType（mysql/postgresql/…），非 JDBC 可空 */
    private String dbType;

    /** 随包驱动信息（方案 §2.4）：许可核查用，非 JDBC 连接器为空 */
    private Driver driver;

    /** 操作清单（结构与 ConnectorDescriptor 一致，驱动组件物料生成） */
    private List<ConnectorDescriptor.OperationDef> operations = new ArrayList<>();

    /** Connection 配置 schema（驱动连接管理页表单动态渲染） */
    private Map<String, ConnectorDescriptor.ConfigField> configSchema = new LinkedHashMap<>();

    /**
     * 上传校验：type / label / version / engine 必填，engine 取值合法，type 命名规范。
     *
     * @throws ConnectorException 校验失败时抛出（提示落给上传者）
     */
    public void validate() {
        if (type == null || type.isBlank()) {
            throw new ConnectorException("MANIFEST_INVALID", "manifest.json 缺少必填字段 type");
        }
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new ConnectorException("MANIFEST_INVALID",
                    "type 命名不合法（须字母开头，仅字母/数字/_/-，≤64 位）：" + type);
        }
        if (label == null || label.isBlank()) {
            throw new ConnectorException("MANIFEST_INVALID", "manifest.json 缺少必填字段 label");
        }
        if (version == null || version.isBlank()) {
            throw new ConnectorException("MANIFEST_INVALID", "manifest.json 缺少必填字段 version");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new ConnectorException("MANIFEST_INVALID",
                    "version 命名不合法（仅字母/数字/./_/-，≤32 位）：" + version);
        }
        if (engine == null || !ENGINES.contains(engine)) {
            throw new ConnectorException("MANIFEST_INVALID",
                    "engine 取值不合法（http / jvm / native）：" + engine);
        }
        if ("jvm".equals(engine) && (mainClass == null || mainClass.isBlank())) {
            throw new ConnectorException("MANIFEST_INVALID",
                    "engine=jvm 时 manifest.json 必须提供 mainClass（反射实例化的 Connector 实现类全限定名）");
        }
    }

    /**
     * 随包驱动声明（方案 §2.4 红线三、许可备注）。
     *
     * <p>只是<b>声明</b>，不参与装载：真实生效的驱动版本由 {@code runtime-info} 从
     * {@code DatabaseMetaData.getDriverVersion()} 读出（§4.2.1）。两者对不上说明包在说谎，
     * 前端在连接器详情页比对后告警。
     *
     * <p>{@code license} 用于交付前的第三方组件授权核查（国企/金融/信创招标常见）：
     * MySQL Connector/J 是 GPL-2.0 + FOSS Exception、Oracle ojdbc 是 OTN、达梦金仓随产品授权。
     */
    @Data
    public static class Driver {

        /** 驱动坐标名，如 "org.postgresql:postgresql" */
        private String name;

        /** 驱动版本，如 "42.7.11" */
        private String version;

        /** 许可标识，如 "BSD-2-Clause" */
        private String license;
    }
}