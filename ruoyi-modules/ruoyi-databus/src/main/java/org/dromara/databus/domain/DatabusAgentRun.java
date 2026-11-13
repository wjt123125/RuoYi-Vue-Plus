package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * AI agent 一次任务对象 databus_agent_run
 * <p>
 * run_id 是交给前端与 AI 的唯一句柄（UUID，不可枚举）；user_id / permissions 是发起时的身份与权限快照，
 * 工具层只能据此干活，不接受入参里的身份。next_seq 是 step 与 message 共用的 seq 号源。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_agent_run")
public class DatabusAgentRun extends BaseEntity {

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * run 句柄（UUID，交给前端与 AI）
     */
    private String runId;

    /**
     * 发起人（身份快照来源，工具层据此 setCreateBy）
     */
    private Long userId;

    /**
     * 用户原话
     */
    private String intent;

    /**
     * SnailAI 会话 id
     */
    private String conversationId;

    /**
     * 已用纠错轮数，上限取 databus.agent.max-rounds
     */
    private Integer round;

    /**
     * 状态（RUNNING / FINISHED / FAILED / STOPPED / EXPIRED / SUPERSEDED）
     */
    private String status;

    /**
     * 非幂等写的一次性人工授权（0未授权 1已授权），不跨 run 继承
     */
    private String sideEffectApproved;

    /**
     * 发起时的权限点快照（JSON 数组）
     */
    private String permissions;

    /**
     * AI 动手前的链路快照（最新一份，多轮见 databus_agent_snapshot）
     */
    private String beforePayload;

    /**
     * 最终报告六段（JSON 或 Markdown）
     */
    private String report;

    /**
     * run TTL，超时置 EXPIRED（惰性判定，无扫描任务）
     */
    private LocalDateTime expireTime;

    /**
     * step 与 message 共用的 seq 号源，条件 UPDATE 原子自增
     */
    private Integer nextSeq;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注
     */
    private String remark;

}