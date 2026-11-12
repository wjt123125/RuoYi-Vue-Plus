package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusAgentMessage;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI agent 消息返回对象：前端按 seq 保序回放。
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusAgentMessage.class)
public class AgentMessageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 所属 run
     */
    private String runId;

    /**
     * run 内单调递增序号
     */
    private Integer seq;

    /**
     * 事件编码
     */
    private String eventCode;

    /**
     * canvas 事件带的链路 id
     */
    private Long chainId;

    /**
     * 载荷
     */
    private String content;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}