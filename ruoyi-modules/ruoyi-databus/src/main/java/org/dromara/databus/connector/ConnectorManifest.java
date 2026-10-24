package org.dromara.databus.connector;

import lombok.Data;
import org.dromara.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 连接器包 manifest.json 解析模型（万物皆包，databus-material-market.md §15.1）。
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

    /** Connector 类型标识（全局唯一），如 "bpmHttp" */
    private String type;

    /** 展示标签，如 "BPM HTTP 连接器" */
    private String label;

    /** 图标名（Iconify 字符串），如 "ph:plugs" */
    private String icon;

    /** 版本号（同 type 升版本＝热更新 def 行） */
    private String version;

    /** 引擎档（http / jvm / native，§15.2 光谱） */
    private String engine;

    /** 操作清单（结构与 ConnectorDescriptor 一致，驱动组件物料生成） */
    private List<ConnectorDescriptor.OperationDef> operations = new ArrayList<>();

    /** Connection 配置 schema（驱动连接管理页表单动态渲染） */
    private Map<String, ConnectorDescriptor.ConfigField> configSchema = new LinkedHashMap<>();

    /**
     * 上传校验：type / label / version / engine 必填，engine 取值合法，type 命名规范。
     *
     * @throws ServiceException 校验失败时抛出（提示落给上传者）
     */
    public void validate() {
        if (type == null || type.isBlank()) {
            throw new ServiceException("manifest.json 缺少必填字段 type");
        }
        if (!TYPE_PATTERN.matcher(type).matches()) {
            throw new ServiceException("type 命名不合法（须字母开头，仅字母/数字/_/-，≤64 位）：" + type);
        }
        if (label == null || label.isBlank()) {
            throw new ServiceException("manifest.json 缺少必填字段 label");
        }
        if (version == null || version.isBlank()) {
            throw new ServiceException("manifest.json 缺少必填字段 version");
        }
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw new ServiceException("version 命名不合法（仅字母/数字/./_/-，≤32 位）：" + version);
        }
        if (engine == null || !ENGINES.contains(engine)) {
            throw new ServiceException("engine 取值不合法（http / jvm / native）：" + engine);
        }
    }
}
