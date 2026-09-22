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
import org.dromara.databus.connector.Connection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * 当前流程执行可用的连接实例注册表：{@code connectionId → Connection}。
     * <p>组件层通过 {@link #getConnection(String)} 取出后传给对应 Connector（决策 9.3）。
     * <p>Connector 类型注册表不放这里（Connector 是无状态单例，放 {@code ConnectorRegistry} Spring Bean）。
     */
    private final Map<String, Connection> connections = new LinkedHashMap<>();

    /**
     * 组件自报的本步人话摘要。
     * <p>必须用 ThreadLocal 而非普通字段：WHEN 并行时多个节点共享同一个 DatabusContext，
     * 但 process 与执行后采集钩子在同一工作线程内成对执行，线程隔离即节点隔离。
     * 采集钩子消费后立即 remove，避免线程池线程复用造成摘要串台。
     */
    private final ThreadLocal<String> stepSummary = new ThreadLocal<>();

    /**
     * 循环索引占位符机制（FOR/WHILE/ITERATOR，详见 docs/wiki/databus-loop-component.md）。
     * <p>
     * 三层 ThreadLocal 状态全部线程隔离：WHEN 并行体在工作线程执行时各自重建，
     * 不与主线程或其他分支串台。状态由节点执行前的生命周期钩子驱动
     * （{@code LoopSupport.sync}），组件与上下文自身不感知循环进出：
     * <ul>
     *     <li>{@link #loopVarStack}：当前线程仍在体的循环变量名栈（外→内），
     *     深度由 LiteFlow 的 getLoopIndex/getPreNLoopIndex 探测，进层压名、出层截断，
     *     无需监听循环结束事件；</li>
     *     <li>{@link #pendingLoopVars}：循环控制组件（forLoop/iteratorLoop）在
     *     processXxx 时注册的自定义变量名，首个循环体节点执行前被消费一次；
     *     0 轮循环残留的待消费名由下一个循环控制节点执行前的钩子清空；</li>
     *     <li>{@link #loopIndexMap}：变量名 → 当前轮下标（0 基），read/write/resolve
     *     入口只对已注册的名字做词边界替换，未注册的 {@code $xxx} 原样不动。</li>
     * </ul>
     */
    private final ThreadLocal<Deque<String>> loopVarStack = ThreadLocal.withInitial(ArrayDeque::new);

    private final ThreadLocal<Deque<String>> pendingLoopVars = ThreadLocal.withInitial(ArrayDeque::new);

    private final ThreadLocal<Map<String, Integer>> loopIndexMap = ThreadLocal.withInitial(LinkedHashMap::new);

    /** 各层默认变量名（不含 {@code $}）：最外层 $i、第二层 $j、第三层 $k。 */
    public static final List<String> DEFAULT_LOOP_VARS = List.of("i", "j", "k");

    /** 最大支持嵌套层数（与 roadmap 阶段 4 约定一致）。 */
    public static final int MAX_LOOP_DEPTH = 3;

    /** 自定义循环变量名校验：字母/下划线开头，字母数字下划线，不含 {@code $}。 */
    private static final Pattern LOOP_VAR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 合法数据空间名（EL tag 形如 {@code http1}/{@code boCreate2}），命中可直接拼 {@code $.tag}。
     */
    private static final Pattern SAFE_TAG = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
     * @param path JSONPath，如 {@code $.user.name}；循环体内可含已注册索引占位符
     *             （如 {@code $.items[$i].name}）；整串恰为 {@code $i} 时返回当前轮下标整数
     * @throws ServiceException 路径不存在时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T read(String path) {
        Integer index = directLoopIndex(path);
        if (index != null) {
            return (T) index;
        }
        try {
            return (T) document.read(substituteLoopVars(path));
        } catch (PathNotFoundException e) {
            throw new ServiceException("JSON路径不存在: " + path, e);
        }
    }

    /**
     * 读取指定路径的值并按 {@link TypeRef} 转换。
     */
    public <T> T read(String path, TypeRef<T> typeRef) {
        try {
            return document.read(substituteLoopVars(path), typeRef);
        } catch (PathNotFoundException e) {
            throw new ServiceException("JSON路径不存在: " + path, e);
        }
    }

    /**
     * 读取指定路径的值（路径不存在时返回 {@code null}）。
     */
    public <T> T readOptional(String path) {
        Integer index = directLoopIndex(path);
        if (index != null) {
            return (T) index;
        }
        try {
            return document.read(substituteLoopVars(path));
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
     * @param path  JSONPath，如 {@code $.user.name}；循环体内可含已注册索引占位符
     *              （如 {@code $.out[$i].name}）
     * @param value 要写入的值
     */
    public void write(String path, Object value) {
        String resolvedPath = substituteLoopVars(path);
        try {
            document.set(resolvedPath, value);
        } catch (Exception e) {
            createPath(resolvedPath);
            document.set(resolvedPath, value);
        }
    }

    /**
     * {@code write(path, value)} 的别名，专供脚本节点调用。
     * <p>规格档 {@code docs/wiki/databus-script-component.md} §6：脚本约定以
     * {@code databusContext.save('$.xxx', value)} 读写数据空间，故补此别名对齐脚本约定；
     * 不改 {@code write} 名字以免破坏既有 Java 组件（{@link org.dromara.databus.component.DatabusNodeComponent#save}）。
     */
    public void save(String path, Object value) {
        write(path, value);
    }

    /**
     * 判断路径是否存在。
     */
    public boolean exists(String path) {
        return PathResolver.pathExists(document, substituteLoopVars(path));
    }

    /**
     * 解析混合路径字符串：先展开已注册的循环索引占位符（{@code $i} 等，
     * 含 {@code ${$.items[$i].name}} 片段内部），再把模板中所有 {@code $.xxx}
     * 片段替换为上下文中的实际值。
     *
     * @param template 含 {@code $.xxx} 片段或循环索引占位符的模板，如 {@code 用户${$.user.name}}
     * @return 替换后的字符串
     */
    public String resolveMixedPath(String template) {
        if (template == null) {
            return null;
        }
        return PathResolver.resolveMixedPath(substituteLoopVars(template), document);
    }

    /**
     * 统一参数解析入口，依据输入类型自动分发：
     * <ul>
     *     <li>整串恰为已注册循环变量（{@code $i}）→ 当前轮下标整数；</li>
     *     <li>纯 JSON 路径字符串 → 展开索引占位符后从上下文读取；</li>
     *     <li>含循环占位符 / 混合路径字符串 → 展开 {@code $i} 与 {@code $.xxx} 片段；</li>
     *     <li>其他类型 → 原样返回</li>
     * </ul>
     */
    public Object resolve(Object input) {
        if (input instanceof String str) {
            Integer direct = directLoopIndex(str);
            if (direct != null) {
                return direct;
            }
            String effective = substituteLoopVars(str);
            if (PathResolver.isPureJsonPath(effective)) {
                return read(effective);
            }
            if (PathResolver.isMixedPathString(effective)) {
                return PathResolver.resolveMixedPath(effective, document);
            }
            // 含循环占位符但不是路径/模板（如 "name-$i"）：返回替换后的字符串
            return effective;
        }
        return input;
    }

    /**
     * 注册一个连接实例到当前上下文（执行前由 Executor / 试运行入口注入）。
     * <p>同一 id 重复注册时覆盖（便于重新执行覆盖旧连接）。
     */
    public void registerConnection(Connection connection) {
        if (connection == null || connection.getId() == null || connection.getId().isBlank()) {
            throw new ServiceException("连接实例缺少 id");
        }
        connections.put(connection.getId(), connection);
    }

    /**
     * 按 connectionId 取已注册的连接实例。
     *
     * @throws ServiceException 未注册时抛出（组件层调 Connector 前必须先取到 Connection）
     */
    public Connection getConnection(String connectionId) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            throw new ServiceException("未注册的 connectionId: " + connectionId
                + "，已注册: " + connections.keySet());
        }
        return conn;
    }

    /**
     * 列出当前上下文所有已注册连接实例（不可变视图）。
     */
    public Map<String, Connection> allConnections() {
        return Collections.unmodifiableMap(connections);
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
     * 组件在 process 收尾时自报一句「人话执行结果」（如「新建 BO 2 个」），
     * 由执行后采集钩子在同一线程消费。
     */
    public void reportStepSummary(String summary) {
        if (summary != null && !summary.isBlank()) {
            stepSummary.set(summary);
        }
    }

    /**
     * 采集钩子消费本线程的步骤摘要并清空（取走后即删，防线程复用串台）。
     */
    public String consumeStepSummary() {
        String value = stepSummary.get();
        stepSummary.remove();
        return value;
    }

    // ==================== 循环索引占位符 ====================

    /**
     * 循环控制组件在 processFor/processIterator 中注册本层变量名。
     * <p>
     * 此刻循环体尚未执行，名字先入待消费队列；首个循环体节点执行前的生命周期钩子
     * 探测到深度增加时按序消费（{@link #reconcileLoopIndices}）。
     *
     * @param name 变量名（不含 {@code $}）；空白表示用默认名，由钩子按深度补 i/j/k
     * @param depth 本循环所在层（1 基），用于默认名选取与超深/重名校验
     */
    public void pushPendingLoopVar(String name, int depth) {
        if (depth > MAX_LOOP_DEPTH) {
            throw new ServiceException("循环嵌套超过 " + MAX_LOOP_DEPTH + " 层，数据总线最多支持 "
                + MAX_LOOP_DEPTH + " 层嵌套");
        }
        if (name == null || name.isBlank()) {
            // 空白入队也行，但默认名在消费时按深度补更直观，这里不入队
            return;
        }
        String trimmed = name.trim();
        if (!LOOP_VAR_NAME.matcher(trimmed).matches()) {
            throw new ServiceException("循环变量名不合法: " + trimmed
                + "（字母/下划线开头，只能含字母、数字、下划线，且不要带 $）");
        }
        if (loopVarStack.get().contains(trimmed)) {
            throw new ServiceException("循环变量名与外层循环重名: $" + trimmed
                + "，嵌套循环请使用不同变量名（如外层 $i、内层 $j）");
        }
        pendingLoopVars.get().addLast(trimmed);
    }

    /**
     * 丢弃全部待消费变量名（0 轮循环残留防护）。
     */
    public void clearPendingLoopVars() {
        pendingLoopVars.get().clear();
    }

    /**
     * 节点执行前按 LiteFlow 探测到的真实循环深度对账本线程变量栈与索引表。
     * <p>
     * 每个节点执行前都调一次（成本极低），因此不依赖循环进出事件：
     * 进层（体首个节点）消费待消费自定义名或补默认名；出层（循环后首个节点）截断栈；
     * 同层逐轮仅刷新索引值。
     *
     * @param depth           当前节点所处循环深度（0 = 不在任何循环体内）
     * @param indexAtDepth    层（1 基）→ 该层当前轮下标（0 基）
     */
    public void reconcileLoopIndices(int depth, IntFunction<Integer> indexAtDepth) {
        Deque<String> names = loopVarStack.get();
        while (names.size() < depth) {
            String custom = pendingLoopVars.get().pollFirst();
            String name = custom != null ? custom : DEFAULT_LOOP_VARS.get(names.size());
            names.addLast(name);
        }
        while (names.size() > depth) {
            names.pollLast();
        }
        Map<String, Integer> indexes = loopIndexMap.get();
        indexes.clear();
        if (depth == 0) {
            // 出清所有循环层：待消费队列一并清掉，防线程池线程复用串到下次执行
            pendingLoopVars.get().clear();
            return;
        }
        int d = 1;
        for (String name : names) {
            Integer idx = indexAtDepth.apply(d);
            if (idx != null) {
                indexes.put(name, idx);
            }
            d++;
        }
    }

    /**
     * 清空本线程全部循环状态（链路执行结束后由 Executor 调用，防线程池复用）。
     */
    public void clearLoopState() {
        loopVarStack.get().clear();
        pendingLoopVars.get().clear();
        loopIndexMap.get().clear();
    }

    /**
     * 整串恰为已注册循环变量（如 {@code $i}）时返回当前下标，否则返回 null。
     */
    public Integer directLoopIndex(String token) {
        if (token == null || token.length() < 2 || token.charAt(0) != '$') {
            return null;
        }
        return loopIndexMap.get().get(token.substring(1));
    }

    /**
     * 把字符串中已注册的循环变量占位符（{@code $i} 等）按词边界替换为当前轮下标数字。
     * <p>
     * 只替换本线程已注册的名字（注册制）：{@code $i} 不会误伤 {@code $index0}，
     * 未注册的 {@code $xxx} 原样保留。无注册变量时零成本直接返回原串。
     */
    public String substituteLoopVars(String text) {
        if (text == null) {
            return null;
        }
        Map<String, Integer> indexes = loopIndexMap.get();
        if (indexes.isEmpty() || text.indexOf('$') < 0) {
            return text;
        }
        String result = text;
        // 长名优先，避免 $item 与 $i 同时存在时前缀干扰（词边界已兜底，双保险）
        List<String> names = new ArrayList<>(indexes.keySet());
        names.sort((a, b) -> b.length() - a.length());
        for (String name : names) {
            Matcher m = Pattern.compile("\\$" + Pattern.quote(name) + "(?![A-Za-z0-9_])").matcher(result);
            result = m.replaceAll(Matcher.quoteReplacement(String.valueOf(indexes.get(name))));
        }
        return result;
    }

    /**
     * 当场拍摄指定数据空间 {@code $.<tag>} 子树的 JSON 快照。
     * <p>必须在节点执行结束的当下调用并序列化为字符串：循环中同一 tag 会执行多轮，
     * 事后再读只能看到最后一轮的状态。
     *
     * @param tag 组件数据空间标识
     * @return 子树 JSON；tag 为空或该子树不存在时返回 null
     */
    public String snapshotDataSpace(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        String path = SAFE_TAG.matcher(tag).matches()
            ? "$." + tag
            : "$['" + tag.replace("'", "\\'") + "']";
        Object subtree = readOptional(path);
        return subtree == null ? null : JsonCodec.toJson(subtree);
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
