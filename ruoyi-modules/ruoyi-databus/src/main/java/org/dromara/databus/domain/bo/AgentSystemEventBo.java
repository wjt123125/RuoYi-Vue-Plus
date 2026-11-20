package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 前端上报的系统事件（客观事实，不是用户原话）。
 * <p>
 * 前端只说「发生了什么」（code + payload），用户可见文案与模型可见指令都由
 * {@link org.dromara.databus.agent.AgentSystemEventCatalog} 维护，
 * 内部提示词因此不会混进用户气泡。
 *
 * @author databus
 */
@Data
public class AgentSystemEventBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 事件码，见 AgentSystemEventCatalog
     */
    @NotBlank(message = "事件码不能为空")
    @Size(max = 64, message = "事件码长度不能超过{max}个字符")
    private String code;

    /**
     * 事件负载（组件编码、名称、版本等），键名由事件码约定
     */
    private Map<String, Object> payload;
}
