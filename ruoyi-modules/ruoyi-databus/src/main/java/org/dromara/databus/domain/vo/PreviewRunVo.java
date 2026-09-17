package org.dromara.databus.domain.vo;

import lombok.Data;
import org.dromara.databus.executor.DatabusExecutionResult;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 试运行结果视图对象。
 * <p>
 * 阶段 1C：生成并校验 EL 后直接按 EL 真执行（不落库），返回每步成败耗时与上下文快照。
 *
 * @author databus
 */
@Data
public class PreviewRunVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 由画布生成的 EL 表达式
     */
    private String elStr;

    /**
     * EL 是否校验通过
     */
    private Boolean valid;

    /**
     * 校验失败或生成异常时的提示信息（成功为 null）
     */
    private String message;

    /**
     * 是否已真正执行（EL 校验通过并调用引擎）。
     */
    private Boolean executed;

    /**
     * 执行是否成功（仅 executed=true 时有意义）。
     */
    private Boolean success;

    /**
     * 节点执行步骤（按执行顺序）。
     */
    private List<DatabusExecutionResult.NodeStep> steps;

    /**
     * 执行结束后上下文的 JSON 快照。
     */
    private String contextJson;

    /**
     * 执行失败时的错误信息。
     */
    private String errorMessage;

    public static PreviewRunVo ok(String elStr) {
        PreviewRunVo vo = new PreviewRunVo();
        vo.setElStr(elStr);
        vo.setValid(Boolean.TRUE);
        return vo;
    }

    public static PreviewRunVo fail(String elStr, String message) {
        PreviewRunVo vo = new PreviewRunVo();
        vo.setElStr(elStr);
        vo.setValid(Boolean.FALSE);
        vo.setMessage(message);
        return vo;
    }

    /**
     * EL 校验通过且已真执行：回填执行结果（成功失败都走这里，HTTP 始终 200）。
     */
    public static PreviewRunVo executed(String elStr, DatabusExecutionResult result) {
        PreviewRunVo vo = new PreviewRunVo();
        vo.setElStr(elStr);
        vo.setValid(Boolean.TRUE);
        vo.setExecuted(Boolean.TRUE);
        vo.setSuccess(result.isSuccess());
        vo.setSteps(result.getSteps());
        vo.setContextJson(result.getContextJson());
        if (!result.isSuccess()) {
            vo.setErrorMessage(result.getMessage());
        }
        return vo;
    }

}
