package org.dromara.databus.context;

import com.jayway.jsonpath.DocumentContext;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据总线 JSON 路径解析工具。
 * <p>
 * 提炼自原系统 {@code JsonPathResolver}，但不直接拷贝全量代码：
 * <ul>
 *     <li>纯路径读写直接走 jayway 原生 {@link DocumentContext#read(String)}/{@code set}，本类只做类型判断</li>
 *     <li>保留「混合路径解析」能力：字符串模板中嵌入 {@code ${$.path}} 做替换，这是数据总线参数绑定的特有需求，LiteFlow {@code getContextValue} 不支持</li>
 *     <li>循环索引占位符 {@code $i} 不在本类处理：由 {@link DatabusContext}
 *     按当前线程已注册的循环变量做注册制替换（见 substituteLoopVars）</li>
 * </ul>
 *
 * @author databus
 */
public final class PathResolver {

    /** 匹配 {@code $.xxx} 形式的纯 JSON 路径。 */
    private static final Pattern PURE_PATH_PATTERN = Pattern.compile("^\\$\\.[\\w.$\\[\\]]+$");

    /**
     * 匹配字符串中的 JSONPath 片段，支持两种写法：
     * <ul>
     *     <li>数据总线模板语法 {@code ${$.user.name}}（含花括号）</li>
     *     <li>裸路径 {@code $.user.name}（兼容原系统写法）</li>
     * </ul>
     * group(1) 为花括号内的路径（如 {@code $.user.name}），group(2) 为裸路径。
     */
    private static final Pattern PATH_FRAGMENT_PATTERN =
        Pattern.compile("\\$\\{(\\$\\.[\\w.$\\[\\]]+)\\}|(\\$\\.[\\w.$\\[\\]]+)");

    private PathResolver() {
    }

    /**
     * 判断是否为纯 JSON 路径字符串（如 {@code $.users[0].name}）。
     */
    public static boolean isPureJsonPath(Object input) {
        if (!(input instanceof String str)) {
            return false;
        }
        return PURE_PATH_PATTERN.matcher(str).matches();
    }

    /**
     * 判断字符串是否包含 JSONPath 片段（如 {@code 用户${$.user.name}}）。
     */
    public static boolean containsPathExpression(String value) {
        if (value == null) {
            return false;
        }
        return value.contains("$.");
    }

    /**
     * 判断是否为混合路径字符串：不是纯路径，但包含路径片段。
     */
    public static boolean isMixedPathString(Object input) {
        if (!(input instanceof String str)) {
            return false;
        }
        return !isPureJsonPath(str) && containsPathExpression(str);
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
     * 解析混合路径字符串：把模板中所有 {@code $.xxx} 片段替换为上下文中的实际值。
     * <p>
     * 替换规则：
     * <ul>
     *     <li>{@code null} → {@code "null"}</li>
     *     <li>字符串 → 转义双引号后直接拼接</li>
     *     <li>数值 / 布尔 → toString 直接拼接</li>
     *     <li>对象 / 数组 → JSON 序列化后拼接</li>
     *     <li>路径不存在时保留原路径片段</li>
     * </ul>
     *
     * @param template 含 {@code $.xxx} 片段的模板字符串
     * @param context  jayway 文档上下文
     * @return 替换后的字符串
     */
    public static String resolveMixedPath(String template, DocumentContext context) {
        if (template == null) {
            return null;
        }
        Matcher matcher = PATH_FRAGMENT_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            // group(1) 为 ${$.path} 中的路径；group(2) 为裸路径
            String path = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Object resolved = readQuietly(path, context);
            String replacement = toReplacementString(resolved, path);
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
     * 把解析出的值转成混合路径替换用的字符串。
     */
    private static String toReplacementString(Object value, String originalPath) {
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
        return originalPath;
    }
}
