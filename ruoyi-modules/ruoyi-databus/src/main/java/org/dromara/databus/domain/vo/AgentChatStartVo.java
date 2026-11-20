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

    /**
     * 本轮意图（原样进模型上下文；卡片回执轮次是带 cardId 的结构化说明，不能上屏）
     */
    private String intent;

    /**
     * 用户可见的回执文案（卡片轮次才有）。
     * <p>
     * 与 intent 分开：intent 带 cardId 等给模型看的说明，二者混用就会把内部措辞漏到界面上。
     */
    private String display;

    public AgentChatStartVo() {
    }

    public AgentChatStartVo(String runId, String conversationId, String intent) {
        this(runId, conversationId, intent, null);
    }

    public AgentChatStartVo(String runId, String conversationId, String intent, String display) {
        this.runId = runId;
        this.conversationId = conversationId;
        this.intent = intent;
        this.display = display;
    }

}