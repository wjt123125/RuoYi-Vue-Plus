package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import io.github.linpeilie.annotations.AutoMapping;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.SysDatabusConnection;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 数据总线连接管理业务对象 sys_databus_connection。
 *
 * <p>连接器专属配置一律装在 {@link #config} 里，BO 上没有 endpoint / accessKey 这类平铺字段：
 * 字段清单由连接器自己的 {@code describe().configSchema} 声明，平台侧不认识任何具体字段名。
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
     * 是否启用（Y启用 N禁用）
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 连接器专属配置（BPM 的 endpoint/accessKey/apiSecret、JDBC 的 host/port/database/username/password…）。
     * <p>本表「零 DDL 扩展」的后端承接字段：前端按 connector 的 describe().configSchema 渲染动态表单，
     * 直接把表单值作为本 Map 提交。敏感字段由 Service 按 descriptor 的 ConfigField.sensitive
     * 拆进 credentials 加密列，其余进 config 明文列。
     */
    @AutoMapping(target = "config", ignore = true)
    private Map<String, Object> config;

}
