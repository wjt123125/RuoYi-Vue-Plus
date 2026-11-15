package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 卡片回执业务对象（运行时 §16）。
 * <p>
 * 用户在卡片上点选后，随下一条用户消息回传；服务端据此判定 {@code userConfirmed} 是否成立，
 * 模型自报的 userConfirmed 不单独作数。
 *
 * @author databus
 */
@Data
public class AgentCardReplyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 被操作的卡片 id
     */
    @NotBlank(message = "cardId 不能为空")
    @Size(max = 128, message = "cardId 长度不能超过{max}个字符")
    private String cardId;

    /**
     * 选中的动作键，确认卡为 confirm / cancel
     */
    @NotBlank(message = "action 不能为空")
    @Size(max = 32, message = "action 长度不能超过{max}个字符")
    private String action;

}