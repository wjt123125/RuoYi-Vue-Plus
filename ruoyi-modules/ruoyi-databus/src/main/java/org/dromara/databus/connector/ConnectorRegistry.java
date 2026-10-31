package org.dromara.databus.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接器类型注册表：按 {@link Connector#getType()} 持有所有 Connector 实例。
 *
 * <p>连接器零内置（方案 §1.2）：实例由 {@code PluginManager} 从 zip 包运行时装载后
 * 经 {@link #register(String, String, Connector, ClassLoader)} 动态注册，不再是启动时的
 * Spring Bean 扫描。因此可增可撤，装载/卸载/热更新都改这张表。
 *
 * <p>归属（决策 9.3）：Connector 是无状态单例，放本类（Spring Bean 容器），
 * 不放 {@code DatabusContext}（DatabusContext 只持有 Connection 实例注册表）。
 *
 * <p>组件层取 Connector 范式（§1.4.2）：
 * <pre>{@code
 * Connection conn = getContext().getConnection(connId);
 * Connector connector = SpringUtils.getBean(ConnectorRegistry.class).ofType(conn.getConnectorType());
 * }</pre>
 *
 * @author databus
 */
@Slf4j
@Component
public class ConnectorRegistry {

    private final Map<String, Connector> connectors = new ConcurrentHashMap<>();

    private final Map<String, String> versions = new ConcurrentHashMap<>();

    private final Map<String, ClassLoader> loaders = new ConcurrentHashMap<>();

    private final Set<String> inactiveTypes = ConcurrentHashMap.newKeySet();

    private final Set<String> disabledTypes = ConcurrentHashMap.newKeySet();

    private final Set<String> deprecatedTypes = ConcurrentHashMap.newKeySet();

    /**
     * 动态注册一个由 {@code PluginManager} 装载的连接器实例（含版本与加载器，供卸载/runtime-info）。
     */
    public void register(String type, String version, Connector connector, ClassLoader loader) {
        Connector existing = connectors.put(type, connector);
        versions.put(type, version);
        if (loader != null) {
            loaders.put(type, loader);
        }
        if (existing == null) {
            log.info("注册 Connector type={} version={} impl={}", type, version, connector.getClass().getName());
        } else {
            log.info("热更新 Connector type={} version={}：{} 替换 {}", type, version,
                    connector.getClass().getName(), existing.getClass().getName());
        }
    }

    /**
     * 注销一个连接器（断最后一个强引用，整代旧类交给 GC，§4.3 第 4 步）。
     */
    public void unregister(String type) {
        connectors.remove(type);
        versions.remove(type);
        loaders.remove(type);
        inactiveTypes.remove(type);
        disabledTypes.remove(type);
        deprecatedTypes.remove(type);
    }

    /**
     * 按类型查找 Connector。
     *
     * @param type 连接器类型标识（如 "bpmHttp"）
     * @return 对应的 Connector 实例
     * @throws ConnectorException 已废弃 / 未启用 / 已禁用 / 未安装时抛出
     */
    public Connector ofType(String type) {
        if (deprecatedTypes.contains(type)) {
            throw new ConnectorException("CONNECTOR_DEPRECATED",
                    "连接器 " + type + " 已废弃（终态不可逆），请改用其他连接器");
        }
        if (inactiveTypes.contains(type)) {
            throw new ConnectorException("CONNECTOR_INACTIVE",
                    "连接器 " + type + " 未启用，请先启用后重试");
        }
        if (disabledTypes.contains(type)) {
            throw new ConnectorException("CONNECTOR_DISABLED",
                    "连接器 " + type + " 已禁用，请先启用后重试");
        }
        Connector connector = connectors.get(type);
        if (connector == null) {
            throw new ConnectorException("CONNECTOR_NOT_FOUND",
                    "连接器 " + type + " 未安装，请到物料市场安装后重试（已注册类型: " + connectors.keySet() + "）");
        }
        return connector;
    }

    /**
     * 按类型取实例（找不到返回 null，不抛异常，供卸载/广播等内部流程使用）。
     */
    public Connector get(String type) {
        return connectors.get(type);
    }

    /**
     * 列出所有已注册 Connector（返回快照，保证并发下安全遍历）。
     */
    public Map<String, Connector> all() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(connectors));
    }

    /**
     * 标记未启用（def 行置 INACTIVE 时由 Platform 侧刷新）。
     */
    public void markInactive(String type) {
        inactiveTypes.add(type);
    }

    /**
     * 标记禁用（def 行置 DISABLED 时由 Platform 侧刷新，§1.4.3 启停门禁）。
     */
    public void markDisabled(String type) {
        disabledTypes.add(type);
    }

    /**
     * 标记废弃（def 行置 DEPRECATED 时由 Platform 侧刷新）：终态门禁，同时摘掉未启用/禁用标记，
     * 保证 {@link #ofType(String)} 报「已废弃」而不是过期的「已禁用」。废弃不可逆，故无 clear 方法。
     */
    public void markDeprecated(String type) {
        inactiveTypes.remove(type);
        disabledTypes.remove(type);
        deprecatedTypes.add(type);
    }

    /**
     * 清除未启用标记（def 行 ENABLED 时刷新）。
     */
    public void clearInactive(String type) {
        inactiveTypes.remove(type);
    }

    /**
     * 清除禁用标记（def 行 ENABLED 时刷新）。
     */
    public void clearDisabled(String type) {
        disabledTypes.remove(type);
    }

    /**
     * 取已注册连接器的版本号（无则 null）。
     */
    public String versionOf(String type) {
        return versions.get(type);
    }

    /**
     * 取已注册连接器的加载器（无则 null）。
     */
    public ClassLoader loaderOf(String type) {
        return loaders.get(type);
    }

    /**
     * 列出所有已注册 type（供卸载遍历等）。
     */
    public List<String> types() {
        return new ArrayList<>(connectors.keySet());
    }
}