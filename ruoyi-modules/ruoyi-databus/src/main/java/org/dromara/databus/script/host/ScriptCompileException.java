package org.dromara.databus.script.host;

import java.util.List;

/**
 * 「编不过」专用异常：javac 吐回来的诊断随身携带，保存接口原样结构化回传
 * Web 编辑器——编辑器拿着行列号在代码上标红波浪线，改代码的人不用翻日志。
 *
 * <p>配套一条铁律（2026-10-06 立法）：编译失败整体不落库、不换 FlowBus——
 * 数据库不留坏版本，现网永远跑最近一次编译通过的版本。
 *
 * @author databus
 */
public class ScriptCompileException extends RuntimeException {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** 诊断明细（transient：万一异常被序列化，明细不随行——message 里的摘要还在）。 */
    private final transient List<ScriptDiagnostic> diagnostics;

    public ScriptCompileException(String message, List<ScriptDiagnostic> diagnostics) {
        super(message);
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<ScriptDiagnostic> getDiagnostics() {
        return diagnostics;
    }
}
