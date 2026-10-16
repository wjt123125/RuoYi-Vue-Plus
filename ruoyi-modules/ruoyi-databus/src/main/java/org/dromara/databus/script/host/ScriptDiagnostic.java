package org.dromara.databus.script.host;

/**
 * javac 的一条「牢骚」：编不过时它逐条报上来——哪一行哪一列、什么级别、
 * 说了什么。javax.tools 自带的 Diagnostic 是编译器内部对象，网络上运不了，
 * 这里抄成 record 当「可运输视图」，随 ScriptCompileException 递到 Web 编辑器。
 *
 * @param kind    级别（ERROR/WARNING/MANDATORY_WARNING/NOTE/OTHER——只有 ERROR 才算编不过）
 * @param line    源码行号（1 起；javac 说不清就给 -1）
 * @param column  源码列号（1 起；同上）
 * @param message 诊断文案（javac 输出的原话）
 * @author databus
 */
public record ScriptDiagnostic(String kind, long line, long column, String message) {
}
