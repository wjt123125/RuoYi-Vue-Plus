package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 组件选择上报入参：用户在插入弹层选中一个组件时记一笔真账。
 *
 * @author databus
 */
@Data
public class CmpPickBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 插入场景（prepend/append/replace/insertEdge）
     */
    @NotBlank(message = "场景不能为空")
    private String mode;

    /**
     * 前置组件 def.type；线上插入取边的 source 节点；无锚点可空（记 ANY）
     */
    private String anchorType;

    /**
     * 被选中组件 def.type
     */
    @NotBlank(message = "被选组件类型不能为空")
    private String pickedType;

}
