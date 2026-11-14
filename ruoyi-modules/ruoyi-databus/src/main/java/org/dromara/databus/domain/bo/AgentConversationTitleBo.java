package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 会话重命名入参。
 *
 * @author databus
 */
@Data
public class AgentConversationTitleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 新标题（用户可见，长度对齐 databus_agent_conversation_meta.custom_title）
     */
    @NotBlank(message = "标题不能为空")
    @Size(max = 255, message = "标题不能超过 255 个字符")
    private String title;

}