package org.dromara.databus.domain.bo;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 卡片回执业务对象（运行时 §16）。
 * <p>
 * 用户在卡片上点选后，随下一条用户消息回传；服务端据此判定 {@code userConfirmed} 是否成立，
 * 模型自报的 userConfirmed 不单独作数。
 *
 * @author databus
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentCardReplyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 被操作的卡片 id
     */
    @NotBlank(message = "cardId 不能为空")
    @Size(max = 128, message = "cardId 长度不能超过{max}个字符")
    private String cardId;

    /**
     * 选中的动作键（旧字段，仅兼容历史回执读取；新写入只写 actions）
     */
    @Size(max = 32, message = "action 长度不能超过{max}个字符")
    private String action;

    /**
     * 选中的动作键集合：单选一项，多选多项
     */
    @Size(max = 6, message = "actions 最多 {max} 项")
    private List<String> actions;

    /**
     * 自由输入内容，仅 allowFreeText=true 的卡片允许
     */
    @Size(max = 500, message = "text 长度不能超过{max}个字符")
    private String text;

    /**
     * 用户可见的回执文案（如「已选「A、B」；补充说明……」「取消这次选择」）。
     * <p>
     * 只由服务端在落库时写入：回执的展示含义以数据库为准，前端和历史回放直接读它，
     * 不再自己按选项拼接。客户端传来的同名字段一律被覆盖。
     */
    @Size(max = 600, message = "display 长度不能超过{max}个字符")
    private String display;

}