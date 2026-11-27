package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusAgentRun;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AI agent 任务返回对象（不含权限快照与链路快照）。
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusAgentRun.class)
public class AgentRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * run 句柄
     */
    private String runId;

    /**
     * SnailAI 会话 id
     */
    private String conversationId;

    /**
     * 用户原话；卡片回执轮次是给模型的结构化说明，用户可见文案在回执 JSON 的 display 里
     */
    private String intent;

    /**
     * 上下文附件原始 JSON 数组（前端自己 JSON.parse 回显，后端不反解文本）
     */
    private String attachments;

    /**
     * 状态
     */
    private String status;

    /**
     * 最终报告
     */
    private String report;

    /**
     * 本轮产生的交互卡片（卡片 JSON，按 seq 升序）；刷新后据此还原，旧 run 由前端置灰
     */
    private List<String> cards;

    /**
     * 本轮收到的卡片回执（回执 JSON，按 seq 升序）；回执落在点选后新建的 run 上，前端按 cardId 跨 run 归位
     */
    private List<String> cardReplies;

    /**
     * 本轮用户可见错误（脱敏后的 AgentError JSON，按 seq 升序）；刷新后据此还原错误提示
     */
    private List<String> errors;

    /**
     * 本轮追问建议（run 结束后异步生成，最多 3 条）；空列表表示不展示
     */
    private List<String> followUps;

    /**
     * 本轮是否真正改过链路（存在链路快照即为 true）；前端据此决定是否提供回退入口
     */
    private Boolean changed;

    /**
     * 已用纠错轮数
     */
    private Integer round;

    /**
     * 过期时间
     */
    private LocalDateTime expireTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}