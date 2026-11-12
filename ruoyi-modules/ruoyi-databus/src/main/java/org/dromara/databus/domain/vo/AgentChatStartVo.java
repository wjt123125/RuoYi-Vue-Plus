package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI 对话发起返回对象：run 句柄与会话 id。
 *
 * @author databus
 */
@Data
public class AgentChatStartVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * run 句柄（前端据此打开流并落消息）
     */
    private String runId;

    /**
     * SnailAI 会话 id（前端持久化，用于刷新后恢复）
     */
    private String conversationId;

    public AgentChatStartVo() {
    }

    public AgentChatStartVo(String runId, String conversationId) {
        this.runId = runId;
        this.conversationId = conversationId;
    }

}