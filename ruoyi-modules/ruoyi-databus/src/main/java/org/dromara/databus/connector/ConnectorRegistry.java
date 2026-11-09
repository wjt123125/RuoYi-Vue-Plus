package org.dromara.databus.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 连接器注册表：按 {@code type@version} 持有所有 Connector 实例。
 *
 * <p>连接器零内置（方案 §1.2）：实例由 {@code PluginManager} 从 zip 包运行时装载后
 * 经 {@link #register(String, String, Connector, ClassLoader)} 动态注册，不再是启动时的
 * Spring Bean 扫描。因此可增可撤，装载/卸载/同版本升级都改这张表。
 *
 * <p>身份键（决策 18）：不同 version 是不同连接器，同 type 多版本可并存；
 * 连接行锁定 {@code connector_def_id} 后由业务层解析成 {@code (type, version)} 精确取实例。
 * {@link #ofType(String)} 仅作历史脚本兜底：取该 type 最新注册且启用中的版本。
 *
 * <p>组件层取 Connector 范式（§1.4.2）：
 * <pre>{@code
 * Connection conn = getContext().getConnection(connId);
 * Connector connector = SpringUtils.getBean(ConnectorRegistry.class)
 *         .ofType(conn.getConnectorType(), conn.getConnectorVersion());
 * }</pre>
 *
 * @author databus
 */
@Slf4j
@Component
public class ConnectorRegistry {

    /** type@version → 实例 */
    private final Map<String, Connector> connectors = new ConcurrentHashMap<>();

    /** type@version → version（保留原始版本串，不解析） */
    private final Map<String, String> versions = new ConcurrentHashMap<>();

    /** type@version → 加载器 */
    private final Map<String, ClassLoader> loaders = new ConcurrentHashMap<>();

    /** type → 该 type 下已注册的版本（按注册先后顺序，末尾为最近注册） */
    private final Map<String, Deque<String>> versionOrder = new ConcurrentHashMap<>();

    /** 以下三个门禁集合统一存 type@version，避免多版本互相误伤 */
    private final Set<String> pendingKeys = ConcurrentHashMap.newKeySet();

    private final Set<String> disabledKeys = ConcurrentHashMap.newKeySet();

    private final Set<String> deprecatedKeys = ConcurrentHashMap.newKeySet();

    /**
     * 动态注册一个由 {@code PluginManager} 装载的连接器实例（含版本与加载器，供卸载/runtime-info）。
     */
    public void register(String type, String version, Connector connector, ClassLoader loader) {
        String key = key(type, version);
        Connector existing = connectors.put(key, connector);
        versions.put(key, version);
        if (loader != null) {
            loaders.put(key, loader);
        }
        Deque<String> order = versionOrder.computeIfAbsent(type, k -> new ConcurrentLinkedDeque<>());
        order.remove(version);
        order.addLast(version);
        if (existing == null) {
            log.info("注册 Connector type={} version={} impl={}", type, version, connector.getClass().getName());
        } else {
            log.info("升级 Connector type={} version={}：{} 替换 {}", type, version,
                    connector.getClass().getName(), existing.getClass().getName());
        }
    }

    /**
     * 注销指定版本的连接器（断最后一个强引用，整代旧类交给 GC，§4.3 第 4 步）。
     */
    public void unregister(String type, String version) {
        String key = key(type, version);
        connectors.remove(key);
        versions.remove(key);
        loaders.remove(key);
        pendingKeys.remove(key);
        disabledKeys.remove(key);
        deprecatedKeys.remove(key);
        Deque<String> order = versionOrder.get(type);
        if (order != null) {
            order.remove(version);
            if (order.isEmpty()) {
                versionOrder.remove(type, order);
            }
        }
    }

    /**
     * 按类型查找 Connector（历史脚本兜底）：取该 type 下最近注册且处于启用态的版本。
     *
     * @param type 连接器类型标识（如 "bpmHttp"）
     * @return 对应的 Connector 实例
     * @throws ConnectorException 已废弃 / 未启用 / 已禁用 / 未安装时抛出
     */
    public Connector ofType(String type) {
        String version = latestEnabledVersion(type);
        if (version == null) {
            throw new ConnectorException("CONNECTOR_NOT_FOUND",
                    "连接器 " + type + " 无可用的已启用版本，请到物料市场安装/启用后重试（已注册: " + keys() + "）");
        }
        return ofType(type, version);
    }

    /**
     * 按类型 + 版本精确查找 Connector。
     *
     * @param type    连接器类型标识（如 "bpmHttp"）
     * @param version 版本号；null/空 时退化为 {@link #ofType(String)}（兼容历史连接未锁定版本）
     * @return 对应的 Connector 实例
     * @throws ConnectorException 已废弃 / 未启用 / 已禁用 / 未安装时抛出
     */
    public Connector ofType(String type, String version) {
        if (version == null || version.isBlank()) {
            return ofType(type);
        }
        String key = key(type, version);
        String label = type + " v" + version;
        if (deprecatedKeys.contains(key)) {
            throw new ConnectorException("CONNECTOR_DEPRECATED",
                    "连接器 " + label + " 已废弃（终态不可逆），请改用其他版本");
        }
        if (pendingKeys.contains(key)) {
            throw new ConnectorException("CONNECTOR_PENDING",
                    "连接器 " + label + " 未启用，请先启用后重试");
        }
        if (disabledKeys.contains(key)) {
            throw new ConnectorException("CONNECTOR_DISABLED",
                    "连接器 " + label + " 已禁用，请先启用后重试");
        }
        Connector connector = connectors.get(key);
        if (connector == null) {
            throw new ConnectorException("CONNECTOR_NOT_FOUND",
                    "连接器 " + label + " 未安装，请到物料市场安装后重试（已注册: " + keys() + "）");
        }
        return connector;
    }

    /**
     * 按类型取实例（取最近注册的版本，找不到返回 null，不抛异常）。
     * <p>只服务于 {@link #get(String, String)} 的 version 缺失兜底（历史连接未锁定版本）；
     * 业务侧一律按 (type, version) 精确取。
     */
    public Connector get(String type) {
        String version = latestRegisteredVersion(type);
        return version == null ? null : connectors.get(key(type, version));
    }

    /**
     * 按类型 + 版本取实例（找不到返回 null，不抛异常）。
     */
    public Connector get(String type, String version) {
        return version == null ? get(type) : connectors.get(key(type, version));
    }

    /**
     * 列出所有已注册 Connector（key = type@version，返回快照，保证并发下安全遍历）。
     */
    public Map<String, Connector> all() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(connectors));
    }

    /**
     * 列出所有已注册 key（type@version）。
     */
    public List<String> keys() {
        return new ArrayList<>(connectors.keySet());
    }

    /**
     * 标记指定版本未启用（def 行置 PENDING 时由 Platform 侧刷新）。
     */
    public void markPending(String type, String version) {
        pendingKeys.add(key(type, version));
    }

    /**
     * 标记指定版本禁用（def 行置 DISABLED 时由 Platform 侧刷新，§1.4.3 启停门禁）。
     */
    public void markDisabled(String type, String version) {
        disabledKeys.add(key(type, version));
    }

    /**
     * 标记指定版本废弃（def 行置 DEPRECATED 时由 Platform 侧刷新）：终态门禁，同时摘掉
     * 未启用/禁用标记。废弃不可逆，故无 clear 方法。
     */
    public void markDeprecated(String type, String version) {
        String key = key(type, version);
        pendingKeys.remove(key);
        disabledKeys.remove(key);
        deprecatedKeys.add(key);
    }

    /**
     * 清除指定版本的未启用标记（def 行 ENABLED 时刷新）。
     */
    public void clearPending(String type, String version) {
        pendingKeys.remove(key(type, version));
    }

    /**
     * 清除指定版本的禁用标记（def 行 ENABLED 时刷新）。
     */
    public void clearDisabled(String type, String version) {
        disabledKeys.remove(key(type, version));
    }

    /**
     * 取指定版本连接器的加载器（无则 null）。
     */
    public ClassLoader loaderOf(String type, String version) {
        return version == null ? null : loaders.get(key(type, version));
    }

    /**
     * 列出某 type 下已注册的版本（按注册先后）。
     */
    public List<String> versionsOf(String type) {
        Deque<String> order = versionOrder.get(type);
        return order == null ? List.of() : new ArrayList<>(order);
    }

    /**
     * 最近注册且启用中的版本；没有则 null。
     */
    private String latestEnabledVersion(String type) {
        Deque<String> order = versionOrder.get(type);
        if (order == null) {
            return null;
        }
        List<String> snapshot = new ArrayList<>(order);
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            String version = snapshot.get(i);
            String key = key(type, version);
            if (!connectors.containsKey(key) || pendingKeys.contains(key)
                    || disabledKeys.contains(key) || deprecatedKeys.contains(key)) {
                continue;
            }
            return version;
        }
        return null;
    }

    private String latestRegisteredVersion(String type) {
        Deque<String> order = versionOrder.get(type);
        if (order == null || order.isEmpty()) {
            return null;
        }
        return order.peekLast();
    }

    private static String key(String type, String version) {
        return type + "@" + version;
    }
}