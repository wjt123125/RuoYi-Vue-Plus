package org.dromara.databus.script.host;

/**
 * 脚本编译诊断条目（javax.tools Diagnostic 的可传输视图）。
 *
 * @param kind    诊断级别（ERROR/WARNING/MANDATORY_WARNING/NOTE/OTHER）
 * @param line    源码行号（1 起；未知为 -1）
 * @param column  源码列号（1 起；未知为 -1）
 * @param message 诊断文案
 * @author databus
 */
public record ScriptDiagnostic(String kind, long line, long column, String message) {
}
