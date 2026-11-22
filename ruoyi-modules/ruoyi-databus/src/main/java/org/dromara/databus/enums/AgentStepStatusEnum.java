package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 工具阶段状态（对话 SSE step_start / step_end 与 databus_agent_message.event_code=step 的快照）。
 * <ul>
 *   <li>RUNNING 运行中：step_start 推送，前端标题扫光、阶段列表保持展开</li>
 *   <li>DONE 已完成：step_end 推送，前端停扫光并收进摘要</li>
 *   <li>FAILED 失败：step_end 带 error，前端标红并自动展开原因</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AgentStepStatusEnum {

    /**
     * 运行中
     */
    RUNNING("running", "运行中"),

    /**
     * 已完成
     */
    DONE("done", "已完成"),

    /**
     * 失败
     */
    FAILED("failed", "失败");

    /**
     * 状态编码（传给前端，前端按码判定，不解析文案）。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}
