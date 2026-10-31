package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import io.github.linpeilie.annotations.AutoMapping;
import io.github.linpeilie.annotations.ReverseAutoMapping;
import lombok.Data;
import org.dromara.databus.domain.SysDatabusConnection;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 数据总线连接管理视图对象 sys_databus_connection
 *
 * @author databus
 */
@Data
@AutoMapper(target = SysDatabusConnection.class)
public class SysDatabusConnectionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id
     */
    private Long id;

    /**
     * 连接 ID（全局唯一，组件层引用）
     */
    private String connectionId;

    /**
     * 连接名称
     */
    private String connectionName;

    /**
     * Connector 类型标识
     */
    private String connectorType;

    /**
     * 是否启用（Y启用 N禁用）
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 连接器专属配置回显（只含 config 明文列，如 JDBC 的 host/port/database/username）。
     * <p>安全口径：credentials 加密列整列不下发，敏感字段（如 password）恒为空；
     * 编辑时留空即沿用旧值，填新值才替换。
     */
    @AutoMapping(target = "config", ignore = true)
    @ReverseAutoMapping(target = "config", ignore = true)
    private Map<String, Object> config;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
