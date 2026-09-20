package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.SysDatabusConnection;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据总线连接管理业务对象 sys_databus_connection。
 *
 * <p>前端提交时字段平铺：endpoint / accessKey / apiSecret 等
 * 直接作为 BO 字段（不需要再嵌套 config 子对象），Service 层负责转 Connection.config Map。
 *
 * @author databus
 */
@Data
@AutoMapper(target = SysDatabusConnection.class, reverseConvertGenerate = false)
public class SysDatabusConnectionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（新增为空，编辑必填）
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 连接 ID（全局唯一，组件层引用此值）
     */
    @NotBlank(message = "连接ID不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 64, message = "连接ID长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String connectionId;

    /**
     * 连接名称
     */
    @NotBlank(message = "连接名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "连接名称长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String connectionName;

    /**
     * Connector 类型标识（如 "bpmHttp"）
     */
    @NotBlank(message = "Connector 类型不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 32, message = "Connector 类型长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String connectorType;

    /**
     * 连接地址
     */
    @Size(max = 255, message = "连接地址长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String endpoint;

    /**
     * OpenAPI access_key（CC 身份策略访问凭证）
     */
    @NotBlank(message = "AccessKey 不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 128, message = "access_key 长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String accessKey;

    /**
     * OpenAPI secret（CC 身份策略私钥，敏感字段落 credentials 加密列；编辑时留空表示不修改）
     */
    @Size(max = 128, message = "secret 长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String apiSecret;

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

}
