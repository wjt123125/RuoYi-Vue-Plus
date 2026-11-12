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
 * AI agent 工具调用审计对象 databus_agent_step
 * <p>
 * 只增不改，无 del_flag 与审计五列（对照 databus_execution 的处置）。
 *
 * @author databus
 */
@Data
@TableName("databus_agent_step")
public class DatabusAgentStep implements Serializable {

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
     * run 内单调递增序号
     */
    private Integer seq;

    /**
     * 第几轮
     */
    private Integer round;

    /**
     * 工具名
     */
    private String tool;

    /**
     * 入参摘要（已脱敏，超长截断）
     */
    private String argsDigest;

    /**
     * 返回摘要（已脱敏，超长截断）
     */
    private String resultDigest;

    /**
     * 成功标志（0失败 1成功）
     */
    private String success;

    /**
     * 失败原文（javac / verifyMessage / NodeStep）
     */
    private String errorMessage;

    /**
     * 守卫拒绝原因，非空表示本次被守卫拦下
     */
    private String guardRejectReason;

    /**
     * 耗时毫秒
     */
    private Long costMs;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}