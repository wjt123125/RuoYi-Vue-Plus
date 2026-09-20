package org.dromara.databus.connector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 连接实例：用户在"连接管理"页配置保存的具体连接。
 *
 * <p>三层物料模型（决策 2.2）的第二层：
 * <ol>
 *   <li>Connector 类型（物料市场注册，如 {@code bpmHttp}）</li>
 *   <li><b>Connection 实例（本类）</b>：用户配置一次，存 endpoint / auth / 超时 / 重试</li>
 *   <li>Component 业务组件：只引用 {@code connectionId}，不重复填 endpoint</li>
 * </ol>
 *
 * <p>配置以 {@code Map<String, Object>} 形式存储，便于：
 * <ul>
 *   <li>序列化落库（{@code sys_databus_connection.config} 字段存 JSON）</li>
 *   <li>连接管理页表单按 {@link ConnectorDescriptor#getConfigSchema()} 动态渲染</li>
 *   <li>具体 Connector 实现按自己熟悉的强类型 POJO 反序列化（如 {@code BpmHttpConnectionCfg}）</li>
 * </ul>
 *
 * <p>运行时归属：{@code DatabusContext.connections} 注册表持有当前流程执行可用的连接实例，
 * 组件层通过 {@code getContext().getConnection(connId)} 取出后传给对应 Connector。
 *
 * @author databus
 */
public class Connection {

    /** 连接 ID（全局唯一，组件层通过 connectionId 引用）。落库主键 */
    private String id;

    /** 连接名称（用户可读，连接管理页展示） */
    private String name;

    /** 关联的 Connector 类型标识（如 "bpmHttp"），决定本连接由哪个 Connector 处理 */
    private String connectorType;

    /**
     * 连接配置：Map 形式存储，key 由 {@link ConnectorDescriptor#getConfigSchema()} 定义。
     * 如 bpmHttp 连接含 {@code endpoint / accessKey / apiSecret / timeoutMs / retryCount}（apiSecret 落 credentials）。
     */
    private Map<String, Object> config = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getConnectorType() { return connectorType; }
    public void setConnectorType(String connectorType) { this.connectorType = connectorType; }

    public Map<String, Object> getConfig() { return config; }
    public void setConfig(Map<String, Object> config) { this.config = config; }
}
