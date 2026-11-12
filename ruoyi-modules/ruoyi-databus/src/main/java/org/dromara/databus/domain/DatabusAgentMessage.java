package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI agent 前端消息对象 databus_agent_message
 * <p>
 * 推给前端的文字记录，同时是 seq 的号源（与 step 共用 run.next_seq），前端按 seq 保序。
 * 只增不改，无 del_flag 与审计五列。
 *
 * @author databus
 */
@Data
@TableName("databus_agent_message")
public class DatabusAgentMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属 run
     */
    private String runId;

    /**
     * run 内单调递增序号，前端按此保序
     */
    private Integer seq;

    /**
     * databus:agent:canvas / draft / script-draft / run-end / auth
     */
    private String eventCode;

    /**
     * canvas 事件带的链路 id
     */
    private Long chainId;

    /**
     * 载荷（canvas 事件为 cmpProperty 全量快照）
     */
    private String content;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}