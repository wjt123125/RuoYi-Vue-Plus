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
 * <p>GET /databus/connector/defs 的视图行：连接器零内置（方案 §1.2），只查 def 行。
 * manifest 快照解析回填 label / icon / operations / configSchema，
 * 前端据此动态渲染连接表单（§15.1 前端直渲）。
 *
 * @author databus
 */
@Data
public class DatabusConnectorDefVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id
     */
    private Long id;

    /**
     * 类型键（全局唯一；连接管理页 connectorType 引用此值）
     */
    private String type;

    /**
     * 版本号（manifest 声明）
     */
    private String version;

    /**
     * 引擎档（http / jvm / native）
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
     * 运行层状态（INACTIVE / ENABLED / DISABLED / DEPRECATED）
     */
    private String status;

    /**
     * 运行态健康（OK / MISSING_DRIVER / NOT_LOADED）——派生字段，不落库，每次查询现算。
     *
     * <p>和 status 是两件事：status 说「治理上该不该跑」，health 说「实际跑起来没有、驱动在不在包里」。
     * 一行 ENABLED 但 health=MISSING_DRIVER 的连接器，界面上看得见、点上去连不上，正是要暴露的故障。
     * 前端配色：status≠ENABLED 一律灰；ENABLED 再按 health 分绿（OK）/ 黄（MISSING_DRIVER）/ 红（NOT_LOADED）。
     */
    private String health;

    /**
     * 不健康时的具体原因（人话，前端可直接显示）；health=OK 时为空
     */
    private String healthReason;

    /**
     * 来源（MARKET / UPLOAD / AI / ADMIN / DEV）
     */
    private String source;

    /**
     * 信任档（审批人审核时勾选，§1.3；true 放开试连异常回显与私网豁免）
     */
    private Boolean verified;

    /**
     * zip 落盘目录（plugins/{type}/{version}）
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
     * 上架者登录名（反查 databus_market_item.owner_code：source_id = def.id 且 resource_type = CONNECTOR；
     * 没上架过的连接器为空，前端不渲染 @作者）
     */
    private String ownerCode;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}