package org.dromara.databus.context;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 数据总线执行上下文。
 * <p>
 * 内部持 jayway {@link DocumentContext}，支持完整 JSONPath 读写（{@code $.a.b.c}、数组索引、过滤等）。
 * 保留原系统「混合路径解析」能力（参数模板中 {@code ${$.path}} 替换），这是数据总线参数绑定的特有需求，
 * LiteFlow 原生 {@code getContextValue}（基于 POJO 反射）不支持动态 JSON 文档。
 * <p>
 * 与原系统 {@code OperationContext} 的区别：
 * <ul>
 *     <li>去除 BPM 运行时依赖（userContext / processInstance / taskInstance）</li>
 *     <li>纯路径读写直接走 jayway 原生 API，不重复造轮子</li>
 *     <li>仅保留混合路径解析与自动建路径写入两个数据总线特有能力</li>
 * </ul>
 *
 * @author databus
 */
@Slf4j
public class DatabusContext {

    /**
     * jayway 配置：使用 Jackson 作为 JSON 提供者与映射提供者（与 RuoYi-Vue-Plus 默认 JSON 库一致）。
     */
    private static final Configuration CONFIGURATION = Configuration.builder()
        .jsonProvider(new JacksonJsonProvider())
        .mappingProvider(new JacksonMappingProvider())
        .build();

    private final DocumentContext document;

    private DatabusContext(DocumentContext document) {
        this.document = document;
    }

    /**
     * 创建一个空上下文（根节点为 {@code {}}）。
     */
    public static DatabusContext empty() {
        return new DatabusContext(JsonPath.using(CONFIGURATION).parse(new LinkedHashMap<>()));
    }

    /**
     * 从 JSON 字符串创建上下文。
     */
    public static DatabusContext fromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        return new DatabusContext(JsonPath.using(CONFIGURATION).parse(json));
    }

    /**
     * 从任意对象创建上下文（先序列化为 JSON 字符串再解析，避免 jayway parse(Object) 对 Map/POJO 的兼容问题）。
     */
    public static DatabusContext fromObject(Object obj) {
        if (obj == null) {
            return empty();
        }
        return fromJson(JsonCodec.toJson(obj));
    }

    /**
     * 读取指定路径的值（路径必须存在，否则抛异常）。
     *
     * @param path JSONPath，如 {@code $.user.name}
     * @throws ServiceException 路径不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T read(String path) {
        try {
            return (T) document.read(path);
        } catch (PathNotFoundException e) {
            throw new ServiceException("JSON路径不存在: " + path, e);
        }
    }

    /**
     * 读取指定路径的值并按 {@link TypeRef} 转换。
     */
    public <T> T read(String path, TypeRef<T> typeRef) {
        try {
            return document.read(path, typeRef);
        } catch (PathNotFoundException e) {
            throw new ServiceException("JSON路径不存在: " + path, e);
        }
    }

    /**
     * 读取指定路径的值（路径不存在时返回 {@code null}）。
     */
    public <T> T readOptional(String path) {
        try {
            return document.read(path);
        } catch (PathNotFoundException e) {
            log.debug("JSON路径不存在: {}，返回 null", path);
            return null;
        }
    }

    /**
     * 读取指定路径的值（路径不存在时返回默认值）。
     */
    public <T> T readOptional(String path, T defaultValue) {
        T value = readOptional(path);
        return value != null ? value : defaultValue;
    }

    /**
     * 写入指定路径的值；若父路径不存在则自动创建（对象逐层建 Map，数组按索引扩容）。
     *
     * @param path  JSONPath，如 {@code $.user.name}
     * @param value 要写入的值
     */
    public void write(String path, Object value) {
        try {
            document.set(path, value);
        } catch (Exception e) {
            createPath(path);
            document.set(path, value);
        }
    }

    /**
     * 判断路径是否存在。
     */
    public boolean exists(String path) {
        return PathResolver.pathExists(document, path);
    }

    /**
     * 解析混合路径字符串：把模板中所有 {@code $.xxx} 片段替换为上下文中的实际值。
     *
     * @param template 含 {@code $.xxx} 片段的模板，如 {@code 用户${$.user.name}}
     * @return 替换后的字符串
     */
    public String resolveMixedPath(String template) {
        return PathResolver.resolveMixedPath(template, document);
    }

    /**
     * 统一参数解析入口，依据输入类型自动分发：
     * <ul>
     *     <li>纯 JSON 路径字符串 → 从上下文读取</li>
     *     <li>含动态变量（{@code $i} 等）→ 原样返回，由循环组件延迟解析</li>
     *     <li>混合路径字符串 → 替换 {@code $.xxx} 片段</li>
     *     <li>其他类型 → 原样返回</li>
     * </ul>
     */
    public Object resolve(Object input) {
        if (PathResolver.isPureJsonPath(input)) {
            return read((String) input);
        }
        if (PathResolver.containsDynamicVariable(input)) {
            return input;
        }
        if (PathResolver.isMixedPathString(input)) {
            return resolveMixedPath((String) input);
        }
        return input;
    }

    /**
     * 暴露内部 jayway 文档上下文（供需要直接操作 DocumentContext 的场景使用）。
     */
    public DocumentContext getDocument() {
        return document;
    }

    /**
     * 把当前上下文序列化为 JSON 字符串。
     */
    public String toJsonString() {
        return document.jsonString();
    }

    /**
     * 递归创建路径结构（对象逐层建 Map，数组按索引扩容）。
     * <p>
     * 提炼自原系统 {@code DocumentUtil.createPath}，去除 AWS SDK 依赖。
     */
    private void createPath(String path) {
        if (path == null || !path.startsWith("$.")) {
            throw new IllegalArgumentException("路径必须以 '$.' 开始");
        }
        String[] parts = path.substring(2).split("\\.");
        StringBuilder currentPath = new StringBuilder("$");
        for (String part : parts) {
            if (isArrayNotation(part)) {
                handleArrayPath(currentPath, part);
            } else {
                handleObjectPath(currentPath, part);
            }
        }
    }

    private void handleObjectPath(StringBuilder currentPath, String part) {
        String nextPath = currentPath + "." + part;
        if (!PathResolver.pathExists(document, nextPath)) {
            document.put(currentPath.toString(), part, new LinkedHashMap<>());
        }
        currentPath.append(".").append(part);
    }

    @SuppressWarnings("unchecked")
    private void handleArrayPath(StringBuilder currentPath, String part) {
        String field = part.replaceAll("\\[\\d+]$", "");
        int index = Integer.parseInt(part.replaceAll(".*\\[(\\d+)]$", "$1"));

        String arrayPath = field.isEmpty() ? currentPath.toString() : currentPath + "." + field;
        if (!PathResolver.pathExists(document, arrayPath)) {
            document.put(currentPath.toString(), field.isEmpty() ? "" : field, new ArrayList<>());
        }
        List<Object> list = document.read(arrayPath);
        while (list.size() <= index) {
            list.add(new LinkedHashMap<>());
        }
        document.set(arrayPath, list);

        currentPath.setLength(0);
        currentPath.append(arrayPath).append("[").append(index).append("]");
    }

    private static boolean isArrayNotation(String part) {
        return part.matches("(\\w+)?\\[\\d+]$");
    }
}
