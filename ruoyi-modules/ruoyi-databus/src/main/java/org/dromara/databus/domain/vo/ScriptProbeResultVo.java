package org.dromara.databus.domain.vo;

import lombok.Data;
import org.dromara.databus.script.host.ScriptDiagnostic;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 组件级探针试跑结果：成功与否、耗时、执行后上下文快照、节点级错误与危险 API 提示。
 * <p>
 * 探针节点即注即摘，不写 databus_execution、不进物料面板；本结果只用于 AI 自纠。
 *
 * @author databus
 */
@Data
public class ScriptProbeResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否执行成功
     */
    private Boolean success;

    /**
     * 耗时（毫秒）
     */
    private Long costMs;

    /**
     * 失败信息（成功时为空）
     */
    private String message;

    /**
     * 执行结束后上下文 JSON 快照
     */
    private String output;

    /**
     * 物化：节点类型
     */
    private String nodeType;

    /**
     * 节点级错误（探针单节点，最多一条）
     */
    private List<String> nodeErrors;

    /**
     * 危险 API 命中提示
     */
    private List<String> risks;

    /**
     * 编译诊断明细（编不过时供审阅页标红；跑通为空）
     */
    private List<ScriptDiagnostic> diagnostics;

    /**
     * 编译失败工厂：与保存/采纳同口径，失败也走 R.ok，行列诊断才到得了前端。
     */
    public static ScriptProbeResultVo fail(String message, List<ScriptDiagnostic> diagnostics) {
        ScriptProbeResultVo vo = new ScriptProbeResultVo();
        vo.setSuccess(Boolean.FALSE);
        vo.setMessage(message);
        vo.setDiagnostics(diagnostics);
        return vo;
    }

}