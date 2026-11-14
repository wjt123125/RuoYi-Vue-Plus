package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI agent 会话摘要（按 run 聚合，不建独立会话表）。
 * <p>
 * 会话的唯一事实来源是 databus_agent_run：同一 conversation_id 的多条 run 合为一项。
 *
 * @author databus
 */
@Data
public class AgentConversationVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * SnailAI 会话 id
     */
    private String conversationId;

    /**
     * 标题：用户自定义标题优先，否则取同会话最早一条非 SUPERSEDED run 的问句，
     * 服务层清洗成一行，长度不在这里截死，由前端按栏宽省略
     */
    private String title;

    /**
     * 最近一次 run 的创建时间
     */
    private LocalDateTime lastTime;

    /**
     * 是否有进行中的 run
     */
    private Boolean running;

    /**
     * 该会话的 run 条数
     */
    private Integer runCount;

    /**
     * 是否置顶（meta 覆盖项，无 meta 视为未置顶）
     */
    private Boolean pinned;

    /**
     * 归属工作区 id；空表示默认工作区
     */
    private Long workspaceId;

}