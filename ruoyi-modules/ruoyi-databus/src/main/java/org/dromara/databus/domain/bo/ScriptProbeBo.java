package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 组件级探针试跑入参（AI 工具 {@code databus_component_run}）。
 * <p>
 * 与链路试跑不同：这里不建链路、不落库，只把一段脚本源码编成 Java 组件，
 * 用一个临时节点 id 注进 FlowBus 跑一次，跑完立刻摘掉——验证的是「组件本身能不能跑」。
 *
 * @author databus
 */
@Data
public class ScriptProbeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 脚本正文（完整 Java 类源码，含 @DatabusCmp 主类）
     */
    private String scriptBody;

    /**
     * 试跑入参 JSON（作为上下文根 $），空则按空入参跑
     */
    private String requestData;

}