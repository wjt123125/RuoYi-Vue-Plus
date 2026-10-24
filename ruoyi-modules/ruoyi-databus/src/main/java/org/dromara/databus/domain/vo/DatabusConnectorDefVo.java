package org.dromara.databus.domain.vo;

import lombok.Data;
import org.dromara.databus.connector.ConnectorDescriptor;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 连接器 def 视图对象（连接管理页 CONNECTOR_OPTIONS 动态化 / 市场连接器卡片用）
 *
 * <p>GET /databus/connector/defs 的合并视图行：内置（Spring 扫描注册）与
 * def 行（包上传 / 市场来源）统一形态。内置行 builtin=true（无 def 行），
 * def 行 builtin=false。manifest 快照解析回填 label / icon / operations / configSchema，
 * 前端据此动态渲染连接表单（§15.1 前端直渲）。
 *
 * @author databus
 */
@Data
public class DatabusConnectorDefVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（内置连接器无 def 行，为 null）
     */
    private Long id;

    /**
     * 类型键（全局唯一；连接管理页 connectorType 引用此值）
     */
    private String type;

    /**
     * 版本号（内置连接器无版本概念，为 null）
     */
    private String version;

    /**
     * 引擎档（http / jvm / native；内置连接器为 null）
     */
    private String engine;

    /**
     * 展示标签（manifest / descriptor 的 label）
     */
    private String label;

    /**
     * 图标名（Iconify 字符串）
     */
    private String icon;

    /**
     * 运行层状态（ENABLED / SUSPENDED / DEPRECATED；内置连接器视为 ENABLED）
     */
    private String status;

    /**
     * 来源（MARKET / UPLOAD / AI / ADMIN；内置连接器为 null）
     */
    private String source;

    /**
     * 是否内置（Spring @Component 扫描注册，无 def 行；内置不走启停/卸载端点）
     */
    private Boolean builtin;

    /**
     * zip 落盘目录（plugins/{type}/{version}；内置为 null）
     */
    private String packagePath;

    /**
     * 操作清单（组件物料生成用；结构同 ConnectorDescriptor）
     */
    private List<ConnectorDescriptor.OperationDef> operations;

    /**
     * Connection 配置 schema（连接表单动态渲染；字段名 → 字段定义）
     */
    private Map<String, ConnectorDescriptor.ConfigField> configSchema;

    /**
     * 上传者 id
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
