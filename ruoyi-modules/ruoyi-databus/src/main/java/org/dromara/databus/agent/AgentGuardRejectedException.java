package org.dromara.databus.agent;

import java.io.Serial;

/**
 * 守卫拒绝异常（方案 §十五 守卫 ②–⑤）。
 * <p>
 * 工具层捕获后转成 JSON 错误串返回给模型，不向上抛（抛异常会变 ToolExecutionException）。
 * {@code runId} 可能为空：上下文缺失时无法定位 run，此时只记日志不落 step。
 * <p>
 * 拒绝原因用枚举而不是中文文案：工具层据此映射 {@code errorType}，
 * 缺少权限点与任务上下文不可用对模型的自纠策略完全不同（前者换人，后者重开会话）。
 * 枚举值写全语义而不是单词，避免调用方猜。
 *
 * @author databus
 */
public class AgentGuardRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 被拒绝的 run 句柄，可为空。
     */
    private final String runId;

    private final GuardReason reason;

    public AgentGuardRejectedException(String runId, GuardReason reason, String message) {
        super(message);
        this.runId = runId;
        this.reason = reason == null ? GuardReason.TASK_CONTEXT_UNAVAILABLE : reason;
    }

    public String getRunId() {
        return runId;
    }

    public GuardReason getReason() {
        return reason;
    }

    /**
     * 拒绝原因分类：取值即结论，避免只写一个单词导致调用方与告警侧理解歧义。
     * <p>
     * 工具层按此映射 errorType：{@link #MISSING_PERMISSION_POINT} → PERMISSION（不可重试），
     * 其余两者 → CONFIG（不可重试，需用户重新发起任务或恢复会话）。
     */
    public enum GuardReason {

        /**
         * 当前用户缺少本次调用所需的权限点（{@code AgentGuard} 校验权限快照不通过）。
         * 换账号或由有权限的人操作，模型自行重试无意义。
         */
        MISSING_PERMISSION_POINT,

        /**
         * 任务上下文不可用：conversationId 为空、会话没有进行中的任务、同会话多个进行中任务无法定位身份、
         * 任务缺发起人快照、任务权限快照损坏。属于会话/任务本身的问题，不是参数问题。
         */
        TASK_CONTEXT_UNAVAILABLE,

        /**
         * 任务已过期（超过 {@code DatabusAgentProperties} 配置的有效期），需用户重新发起。
         */
        TASK_EXPIRED
    }

}