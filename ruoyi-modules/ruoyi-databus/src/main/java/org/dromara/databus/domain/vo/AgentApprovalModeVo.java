package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话审批模式回包。
 *
 * @author databus
 */
@Data
public class AgentApprovalModeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前生效模式（FULL 在部署未开放时已归一为 MANUAL）
     */
    private String mode;

    /**
     * 本部署是否开放「完全访问」档（前端据此置灰）
     */
    private Boolean allowFullAccess;

}