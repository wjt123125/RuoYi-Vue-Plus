package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.dromara.databus.constant.AgentConstants.ERROR_CONFIG;
import static org.dromara.databus.constant.AgentConstants.ERROR_PERMISSION;
import static org.dromara.databus.constant.AgentConstants.ERROR_RUNTIME;
import static org.dromara.databus.constant.AgentConstants.ERROR_VALIDATION;
import static org.dromara.databus.constant.AgentConstants.PERMISSION_READ;

/**
 * 工具执行壳：守卫 → 推送阶段 → 业务 → 审计 → 阶段收尾，并把异常统一转成 JSON 错误串。
 * <p>
 * 由 DatabusAgentTools 委托调用，不直接注册为工具。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolExecutionSupport {

    private final AgentGuard agentGuard;
    private final AgentStepRecorder stepRecorder;
    private final AgentCardChannel cardChannel;
    private final AgentToolStepRegistry toolStepRegistry;

    /**
     * 统一执行壳：守卫 → 推送阶段 → 业务 → 审计 → 阶段收尾。守卫拒绝与执行失败都转成 JSON 错误串。
     * <p>
     * 阶段事件（step_start / step_end）在守卫通过后才推：没有身份定位不到 run，也还没真正开始执行。
     * 推送失败只影响观感，不改变工具返回（通道内部已吞异常）。
     *
     * @param tool 工具名
     * @param args 入参快照（审计 + 标题摘要）
     * @param body 业务体
     * @return JSON 结果
     */
    public String execute(String tool, Map<String, Object> args,
                          Function<AgentRunContext, Map<String, Object>> body) {
        return execute(tool, args, PERMISSION_READ, body);
    }

    public String execute(String tool, Map<String, Object> args, String permission,
                          Function<AgentRunContext, Map<String, Object>> body) {
        long began = System.currentTimeMillis();
        String argsJson = JsonUtils.toJsonString(args);
        AgentRunContext context = null;
        AgentStepEvent step = null;
        try {
            context = agentGuard.check(permission);
            // 标题来自工具定义上的 @AgentToolStep（启动时已由注册表收集），这里只做拼装
            step = AgentStepEvent.running(UUID.randomUUID().toString(), tool,
                toolStepRegistry.titleOf(tool, args));
            cardChannel.sendStep(context.runId(), step);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.putAll(body.apply(context));
            String result = JsonUtils.toJsonString(payload);
            stepRecorder.recordSuccess(context, tool, argsJson, result, System.currentTimeMillis() - began);
            finishStep(context, step, false);
            return result;
        } catch (AgentGuardRejectedException e) {
            boolean permissionDenied = e.getReason() == AgentGuardRejectedException.GuardReason.MISSING_PERMISSION_POINT;
            String result = errorJson(e.getMessage(), permissionDenied ? ERROR_PERMISSION : ERROR_CONFIG, false);
            stepRecorder.recordReject(e.getRunId(), tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            finishStep(context, step, true);
            return result;
        } catch (ServiceException e) {
            // 业务校验（如轮数上限）直接给出原因，不套「工具执行失败」前缀
            String result = errorJson(e.getMessage(), ERROR_VALIDATION, true);
            stepRecorder.recordFailure(context, tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            finishStep(context, step, true);
            return result;
        } catch (IllegalArgumentException e) {
            // 参数、游标、页大小非法：模型改参数可重试
            String result = errorJson(e.getMessage(), ERROR_VALIDATION, true);
            stepRecorder.recordFailure(context, tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            finishStep(context, step, true);
            return result;
        } catch (Exception e) {
            log.warn("[databus-agent] 工具 {} 执行失败", tool, e);
            String result = errorJson("工具执行失败：" + e.getMessage(), ERROR_RUNTIME, true);
            stepRecorder.recordFailure(context, tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            finishStep(context, step, true);
            return result;
        }
    }

    /**
     * 阶段收尾：成功 / 失败都推 step_end，让前端把「运行中」的扫光停掉。
     * <p>
     * 失败原因不随事件下发：原始异常只进日志与返回给模型的 JSON，用户侧只看到状态。
     *
     * @param context 身份快照，守卫拒绝时为空
     * @param step    开始事件，守卫拒绝时为空
     * @param failed  是否失败
     */
    public void finishStep(AgentRunContext context, AgentStepEvent step, boolean failed) {
        if (context == null || step == null) {
            return;
        }
        cardChannel.sendStep(context.runId(), failed
            ? AgentStepEvent.failed(step)
            : AgentStepEvent.done(step));
    }

    private static String errorJson(String message, String errorType, boolean retryable) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("errorType", errorType);
        payload.put("error", message == null || message.isBlank() ? "未知错误" : message);
        payload.put("retryable", retryable);
        return JsonUtils.toJsonString(payload);
    }
}
