package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.SysDatabusConnection;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

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
     * 连接地址
     */
    private String endpoint;

    /**
     * 默认用户名
     */
    private String username;

    /**
     * 默认密码
     */
    private String password;

    /**
     * IP 白名单（JSON 数组字符串）
     */
    private String ipWhiteList;

    /**
     * HTTP 超时（毫秒）
     */
    private Integer timeout;

    /**
     * 失败重试次数
     */
    private Integer retryCount;

    /**
     * 是否启用（Y启用 N禁用）
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
