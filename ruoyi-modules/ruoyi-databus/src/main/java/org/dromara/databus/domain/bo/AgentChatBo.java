package org.dromara.databus.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * AI 对话发起业务对象。
 * <p>
 * conversationId 为空时由后端生成并回传（前端只存这个 id，run 状态归后端）。
 * content 可以不填：只带附件（选中一段日志直接问）也是合法的一条消息。
 *
 * @author databus
 */
@Data
public class AgentChatBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户原话（可为空，与 attachments 至少有一个）
     */
    @Size(max = 4000, message = "内容长度不能超过{max}个字符")
    private String content;

    /**
     * 上下文附件（终端日志 / 试跑结果 / 编译报错 / 画布节点等的选段或引用）
     */
    @Valid
    @Size(max = 20, message = "单条消息最多带{max}条上下文")
    private List<AgentContextItemBo> attachments;

    /**
     * SnailAI 会话 id（为空则新开一个会话）
     */
    @Size(max = 64, message = "会话id长度不能超过{max}个字符")
    private String conversationId;

    /**
     * 卡片回执：用户在确认 / 选择卡片上点了哪些项（可选）
     */
    @Valid
    private AgentCardReplyBo cardReply;

    /**
     * 系统事件：草稿采纳 / 拒绝等客观事实（可选）。
     * 只带事件、没有正文也是合法的一条消息，展示文案由后端目录生成。
     */
    @Valid
    private AgentSystemEventBo systemEvent;

}