package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话发起业务对象。
 * <p>
 * conversationId 为空时由后端生成并回传（前端只存这个 id，run 状态归后端）；
 * chainId 是当前画布链路，可为空（从零建）。
 *
 * @author databus
 */
@Data
public class AgentChatBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户原话
     */
    @NotBlank(message = "内容不能为空")
    @Size(max = 1000, message = "内容长度不能超过{max}个字符")
    private String content;

    /**
     * SnailAI 会话 id（为空则新开一个会话）
     */
    @Size(max = 64, message = "会话id长度不能超过{max}个字符")
    private String conversationId;

    /**
     * 当前画布链路 id，可为空
     */
    private Long chainId;

}