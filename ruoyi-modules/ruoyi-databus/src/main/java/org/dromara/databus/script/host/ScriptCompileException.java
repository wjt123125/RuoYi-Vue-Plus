package org.dromara.databus.script.host;

import java.util.List;

/**
 * 脚本源码编译失败异常：携带行列级诊断，供保存接口结构化回传 Web 编辑器。
 * <p>
 * 保存管线语义（2026-10-06 立法）：编译失败整体不落库、不换 FlowBus，
 * 现网永远运行最近一次编译通过版本。
 *
 * @author databus
 */
public class ScriptCompileException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final transient List<ScriptDiagnostic> diagnostics;

    public ScriptCompileException(String message, List<ScriptDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<ScriptDiagnostic> getDiagnostics() {
        return diagnostics;
    }
}
