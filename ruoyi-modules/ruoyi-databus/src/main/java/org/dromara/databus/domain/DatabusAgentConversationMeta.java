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
 * AI agent 会话覆盖信息 databus_agent_conversation_meta
 * <p>
 * 会话本身由 databus_agent_run 聚合，本表只存用户改过的覆盖项（标题 / 置顶 / 工作区 / 软删），
 * 一个 conversation_id 一行。del_flag 不做逻辑删除注解：软删过的会话再被重命名时要能找回同一行改写，
 * 加注解会让 selectOne 查不到它从而撞唯一键。
 *
 * @author databus
 */
@Data
@TableName("databus_agent_conversation_meta")
public class DatabusAgentConversationMeta implements Serializable {

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
     * 工作区 id；空=默认工作区
     */
    private Long workspaceId;

    /**
     * 会话标题：自动生成或用户改名。null 表示未命名（前端显示占位），非空即定稿，不再覆盖
     */
    private String title;

    /**
     * 置顶（0否 1是）
     */
    private String pinned;

    /**
     * 软删标志（0存在 1删除）
     */
    private String delFlag;

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