package org.dromara.databus.connector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连接器类型注册表：按 {@link Connector#getType()} 注册所有 Connector Bean。
 *
 * <p>1D-P0 阶段实现路线（决策 2.5）：Spring {@code @Component} 自动扫描注册，
 * 所有 Connector 实现类标注 {@code @Component} 即自动加入本注册表。
 *
 * <p>未来升级路径：
 * <ul>
 *   <li>1D-P1+：SPI（{@code META-INF/services}）独立 jar，运行时动态加载</li>
 *   <li>未来：Spring Boot Starter（{@code databus-connector-xxx-starter}）</li>
 * </ul>
 *
 * <p>归属（决策 9.3）：Connector 是无状态单例，放 Spring Bean 容器（本类），
 * 不放 {@code DatabusContext}（DatabusContext 只持有 Connection 实例注册表）。
 *
 * <p>组件层取 Connector 范式：
 * <pre>{@code
 * Connection conn = getContext().getConnection(connId);
 * Connector connector = SpringUtils.getBean(ConnectorRegistry.class).ofType(conn.getConnectorType());
 * // 或：Connector connector = SpringUtils.getBean(BpmHttpConnector.class);（已知具体类型时）
 * }</pre>
 *
 * @author databus
 */
@Slf4j
@Component
public class ConnectorRegistry {

    private final Map<String, Connector> connectors = new LinkedHashMap<>();

    /**
     * Spring 构造注入：所有标注 {@code @Component} 的 Connector 实现类自动加入。
     */
    @Autowired
    public ConnectorRegistry(List<Connector> connectorList) {
        if (connectorList != null) {
            for (Connector connector : connectorList) {
                String type = connector.getType();
                if (type == null || type.isBlank()) {
                    log.warn("Connector {} 的 getType() 返回空，跳过注册", connector.getClass().getName());
                    continue;
                }
                Connector existing = connectors.put(type, connector);
                if (existing != null) {
                    log.warn("Connector type={} 重复注册：{} 覆盖 {}",
                            type, connector.getClass().getName(), existing.getClass().getName());
                } else {
                    log.info("注册 Connector type={} impl={}", type, connector.getClass().getName());
                }
            }
        }
        log.info("ConnectorRegistry 初始化完成，共 {} 个 Connector: {}", connectors.size(), connectors.keySet());
    }

    /**
     * 按类型查找 Connector。
     *
     * @param type 连接器类型标识（如 "bpmHttp"）
     * @return 对应的 Connector 实例
     * @throws ConnectorException 无对应类型的 Connector 时抛出
     */
    public Connector ofType(String type) {
        Connector connector = connectors.get(type);
        if (connector == null) {
            throw new ConnectorException("CONNECTOR_NOT_FOUND",
                    "未找到 type=" + type + " 的 Connector，已注册类型: " + connectors.keySet());
        }
        return connector;
    }

    /**
     * 列出所有已注册 Connector 类型（连接管理页"新建连接"下拉选项用）。
     */
    public Map<String, Connector> all() {
        return Collections.unmodifiableMap(connectors);
    }
}
