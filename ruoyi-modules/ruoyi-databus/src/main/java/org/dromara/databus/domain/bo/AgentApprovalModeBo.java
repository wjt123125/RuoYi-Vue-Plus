package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话审批模式入参。
 *
 * @author databus
 */
@Data
public class AgentApprovalModeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 模式编码 MANUAL/AUTO/FULL/CUSTOM（CUSTOM 暂未开放）
     */
    @NotBlank(message = "审批模式不能为空")
    private String mode;

}