package org.dromara.databus.connector;

/**
 * 连接器协议层抽象。
 *
 * <p>连接器是组件的底层依赖：组件负责编排语义，连接器负责协议交互。
 * 不同连接器的操作集合不同（HTTP 连接器有 createSession/processStart 等操作，
 * DB 连接器有 query/execute 等操作），因此本接口不暴露具体操作方法，
 * 由具体连接器类自行声明业务方法（如 {@code BpmHttpConnector#createSession}）。
 *
 * <p>本接口只规定三件公共能力：
 * <ol>
 *   <li>{@link #getType()} - 类型标识，用于 Connection 关联与注册表查找</li>
 *   <li>{@link #describe()} - 元数据（标签 / 图标 / 操作清单 / 配置 schema），驱动前端物料与表单自动渲染</li>
 *   <li>{@link #testConnection(Connection)} - 连接测试，连接管理页"测试连接"按钮调用</li>
 * </ol>
 *
 * <p>实现约定：
 * <ul>
 *   <li>无状态单例：Spring {@code @Component} 管理，所有状态通过方法参数传入</li>
 *   <li>1D-P0 阶段子包 + {@code @Component} 自动注册（与 ruoyi-databus 一起打包，代码解耦非真拔插）</li>
 *   <li>未来升级 SPI（{@code META-INF/services}）或 Spring Boot Starter</li>
 * </ul>
 *
 * @author databus
 */
public interface Connector {

    /**
     * 连接器类型标识，全局唯一。
     * <p>用于 {@link Connection#getConnectorType()} 关联、{@link ConnectorRegistry#ofType(String)} 查找。
     * 命名约定：协议 + 版本（如 {@code bpmHttp}、{@code bpmHttpV2}、{@code http}、{@code db}）。
     */
    String getType();

    /**
     * 连接器元数据。驱动前端 cmp-defs 物料自动生成与连接管理页表单动态渲染。
     */
    ConnectorDescriptor describe();

    /**
     * 测试连接是否可用。连接管理页"测试连接"按钮调用，返回成功/失败信息。
     *
     * @param connection 连接实例配置（含 endpoint / auth / 超时 / 重试等）
     * @return 测试结果（成功时返回 ok 信息，失败时抛 {@link ConnectorException}）
     */
    String testConnection(Connection connection);
}
