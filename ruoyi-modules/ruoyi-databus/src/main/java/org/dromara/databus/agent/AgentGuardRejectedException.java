package org.dromara.databus.agent;

import java.io.Serial;

/**
 * 守卫拒绝异常（方案 §十五 守卫 ②–⑤）。
 * <p>
 * 工具层捕获后转成 JSON 错误串返回给模型，不向上抛（抛异常会变 ToolExecutionException）。
 * {@code runId} 可能为空：上下文缺失时无法定位 run，此时只记日志不落 step。
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

    public AgentGuardRejectedException(String runId, String reason) {
        super(reason);
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }

}