package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 会话级操作审批模式 databus_agent_approval_setting
 * <p>
 * 一个 conversation_id 一行；没有行等价于 MANUAL。模式随会话走，
 * 但落独立表而非 conversation_meta：执行策略与展示覆盖项的生命周期不同。
 *
 * @author databus
 */
@Data
@TableName("databus_agent_approval_setting")
public class DatabusAgentApprovalSetting implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 会话 id（与 databus_agent_run.conversation_id 对应）
     */
    private String conversationId;

    /**
     * 归属用户
     */
    private Long userId;

    /**
     * 审批模式 MANUAL/AUTO/FULL/CUSTOM
     */
    private String mode;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}