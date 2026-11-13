package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusAgentRun;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

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
     * 用户原话
     */
    private String intent;

    /**
     * 状态
     */
    private String status;

    /**
     * 最终报告
     */
    private String report;

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