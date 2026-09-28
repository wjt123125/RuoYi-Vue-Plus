package org.dromara.databus.context;

import com.jayway.jsonpath.DocumentContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据总线动态表达式片段解析工具。
 * <p>
 * 动态标记统一为 {@code {{ 表达式 }}（}2026-09-24 拍板、2026-09-27 补字段角色规则，
 * 见 databus-expression-syntax.md）：
 * <ul>
 *     <li>整字段恰为单个 {@code {{ 表达式 }}：由 jayway 求值并保留原始类型</li>
 *     <li>模板中嵌入 {@code {{ 表达式 }}：逐个求值后拼接为字符串</li>
 *     <li>不含 {@code {{}：原样传递（写目标位置名 {@code $.xxx} 与普通字面量同走此路径）</li>
 * </ul>
 * 循环索引占位符 {@code $i} 不在本类处理：由 {@link DatabusContext}
 * 按当前线程已注册的循环变量在求值前替换（见 substituteLoopVars）。
 *
 * @author databus
 */
@Slf4j
public final class PathResolver {

    /**
     * 匹配字符串中的 {@code {{ 表达式 }}} 片段（非贪婪），
     * group(1) 为括号内已去首尾空白的表达式文本。
     */
    private static final Pattern EXPRESSION_PATTERN = Pattern.compile("\\{\\{\\s*(.*?)\\s*}}");

    /**
     * 整字段单表达式判定：trim 后恰为一个完整 {@code {{ ... }}，
     * group(1) 为表达式文本。非贪婪匹配，内容含 {@code }}} 时判否，
     * 避免 {@code {{ $.a }} {{ $.b }}} 被误判（语法档 §4.2）。
     */
    private static final Pattern WHOLE_EXPRESSION_PATTERN = Pattern.compile("^\\{\\{\\s*(.*?)\\s*}}$");

    private PathResolver() {
    }

    /**
     * 判断字符串是否包含动态表达式片段（含 {@code {{}）。
     */
    public static boolean containsExpression(String value) {
        return value != null && value.contains("{{");
    }

    /**
     * 整字段判定：trim 后恰好是单个完整表达式、前后无其它字符时返回表达式文本，
     * 否则返回 {@code null}。
     */
    public static String wholeExpression(String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = WHOLE_EXPRESSION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            return null;
        }
        String inner = matcher.group(1);
        return inner.contains("}}") ? null : inner.trim();
    }

    /**
     * 判断对象是否可序列化为 JSON（集合 / Map / 数组）。
     */
    public static boolean isJsonSerializable(Object value) {
        if (value == null) {
            return false;
        }
        Class<?> clazz = value.getClass();
        return value instanceof Collection
            || value instanceof Map
            || clazz.isArray();
    }

    /**
     * 解析嵌入表达式的模板：把 {@code {{ }}} 片段逐个求值后拼接为字符串。
     * <p>
     * 字符串化规则：
     * <ul>
     *     <li>{@code null} 值 → {@code "null"}</li>
     *     <li>字符串 → 转义双引号后直接拼接</li>
     *     <li>数值 / 布尔 → toString 直接拼接</li>
     *     <li>对象 / 数组 → JSON 序列化后拼接</li>
     *     <li>表达式路径不存在 → 打 WARN，原片段原样保留（不中断执行）</li>
     * </ul>
     *
     * @param template 含 {@code {{ }}} 片段的模板
     * @param context  jayway 文档上下文
     * @return 拼接后的字符串
     */
    public static String resolveEmbedded(String template, DocumentContext context) {
        if (template == null) {
            return null;
        }
        Matcher matcher = EXPRESSION_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String fragment = matcher.group();
            String expression = matcher.group(1).trim();
            Object resolved = readQuietly(expression, context);
            if (resolved == null && !pathExists(context, expression)) {
                // 读不到路径：可诊断但不拦执行，片段原样保留
                log.warn("[databus] 表达式取值失败，片段原样保留: {}", expression);
                matcher.appendReplacement(result, Matcher.quoteReplacement(fragment));
                continue;
            }
            String replacement = toReplacementString(resolved, fragment);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * 静默读取路径，路径不存在时返回 {@code null}（不抛异常）。
     */
    public static Object readQuietly(String path, DocumentContext context) {
        try {
            return context.read(path);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断路径在文档中是否存在。
     */
    public static boolean pathExists(DocumentContext context, String path) {
        try {
            context.read(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把求值结果转成嵌入模板替换用的字符串；遇到无法识别的类型时回退为原片段。
     */
    private static String toReplacementString(Object value, String fallbackFragment) {
        if (value == null) {
            return "null";
        }
        if (value instanceof CharSequence) {
            return value.toString().replace("\"", "\\\"");
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (isJsonSerializable(value)) {
            return JsonCodec.toJson(value);
        }
        return fallbackFragment;
    }
}
