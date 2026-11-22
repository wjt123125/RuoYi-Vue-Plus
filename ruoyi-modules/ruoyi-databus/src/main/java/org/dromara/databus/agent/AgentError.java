package org.dromara.databus.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户可见的统一错误信封（设计选择，见 docs/agent/error-handling.md）。
 * <p>
 * 前端只渲染 {@code message}、按 {@code retryable} 决定是否给重试、把 {@code referenceId}
 * 放进次要行；不维护错误码到文案的映射。原始异常、SQL、堆栈只进服务端日志。
 *
 * @param code        机器码，只用于日志/指标/程序分支
 * @param category    错误类别（六类之一）
 * @param retryable   是否可重试
 * @param retryAfterMs 建议等待毫秒数，可为空
 * @param message     用户可见文案（服务端按类别从 i18n 解析）
 * @param referenceId 日志关联编号
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentError(String code, String category, boolean retryable, Long retryAfterMs,
                         String message, String referenceId) {
}
