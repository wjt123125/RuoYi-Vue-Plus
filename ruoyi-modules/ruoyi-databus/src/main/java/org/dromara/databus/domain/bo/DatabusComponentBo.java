package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.DatabusComponent;

import java.io.Serial;
import java.io.Serializable;

/**
 * 组件元信息业务对象 databus_component
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusComponent.class, reverseConvertGenerate = false)
public class DatabusComponentBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 组件编码
     */
    @NotBlank(message = "组件编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "组件编码长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String componentCode;

    /**
     * 组件名称
     */
    @NotBlank(message = "组件名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "组件名称长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String componentName;

    /**
     * 组件分类
     */
    @NotBlank(message = "组件分类不能为空", groups = {AddGroup.class, EditGroup.class})
    private String category;

    /**
     * 组件图标
     */
    private String icon;

    /**
     * 组件描述
     */
    private String description;

    /**
     * 参数 Schema（JSON Schema 格式）
     */
    private String paramSchema;

    /**
     * 输入 Schema
     */
    private String inputSchema;

    /**
     * 输出 Schema
     */
    private String outputSchema;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

}
