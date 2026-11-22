package org.dromara.databus.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dromara.databus.enums.AgentStepStatusEnum;

/**
 * 工具阶段事件（前端「当前阶段」那一行的唯一数据源）。
 * <p>
 * 一次工具调用对应一个 stepId：开始推 {@code step_start}，结束推 {@code step_end}。
 * 前端只维护**一条**可变阶段行，就地更新标题与状态，不保留历史步骤——
 * 历史明细由 databus_agent_step 审计表承担，聊天里不重复存。
 * <p>
 * 失败原因属于模型诊断信息，只进工具返回值与服务端日志，绝不写进本事件。
 *
 * @param stepId 单次调用唯一 id（run 内唯一）
 * @param tool   工具名（{@code AgentConstants.TOOL_*}），排障用
 * @param title  阶段标题（来自工具元数据）
 * @param status 阶段状态编码（{@link AgentStepStatusEnum#getCode()}）
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentStepEvent(String stepId, String tool, String title, String status) {

    /**
     * 开始执行。
     *
     * @param stepId 单次调用唯一 id
     * @param tool   工具名
     * @param title  阶段标题
     * @return 运行中的阶段事件
     */
    public static AgentStepEvent running(String stepId, String tool, String title) {
        return new AgentStepEvent(stepId, tool, title, AgentStepStatusEnum.RUNNING.getCode());
    }

    /**
     * 正常结束。
     *
     * @param running 开始事件
     * @return 已完成事件
     */
    public static AgentStepEvent done(AgentStepEvent running) {
        return new AgentStepEvent(running.stepId(), running.tool(), running.title(),
            AgentStepStatusEnum.DONE.getCode());
    }

    /**
     * 失败结束（不带失败原因：原因只给模型和日志）。
     *
     * @param running 开始事件
     * @return 失败事件
     */
    public static AgentStepEvent failed(AgentStepEvent running) {
        return new AgentStepEvent(running.stepId(), running.tool(), running.title(),
            AgentStepStatusEnum.FAILED.getCode());
    }
}
