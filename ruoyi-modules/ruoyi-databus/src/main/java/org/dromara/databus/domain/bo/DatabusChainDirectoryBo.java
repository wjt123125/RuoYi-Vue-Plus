package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.DatabusChainDirectory;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路目录业务对象 databus_chain_directory
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusChainDirectory.class, reverseConvertGenerate = false)
public class DatabusChainDirectoryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id（新增为空，编辑必填）
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 目录名称（同级唯一）
     */
    @NotBlank(message = "目录名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "目录名称长度不能超过{max}个字符", groups = {AddGroup.class, EditGroup.class})
    private String directoryName;

    /**
     * 父目录id（空/0=根目录）；编辑时可改实现目录拖拽移动，后端做防环校验
     */
    private Long parentId;

    /**
     * 排序（升序，值小在前，默认 0）
     */
    private Integer sort;

    /**
     * 备注
     */
    private String remark;

}
