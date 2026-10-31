package org.dromara.databus.connector;

import java.util.Map;

/**
 * 连接器协议层抽象。
 *
 * <p>连接器是组件的底层依赖：组件负责编排语义，连接器负责协议交互。连接器零内置，
 * 一律作为物料包（zip）上传 → 审核 → {@code PluginClassLoader}（child-first）在线装载，
 * 实例由 {@code ConnectorRegistry} 持有。因此实现类<b>不是</b> Spring {@code @Component}，
 * 由 {@code PluginManager} 反射实例化，本接口只规定公共能力。
 *
 * <p>两层调用契约（决策 §1.4.2）：
 * <ol>
 *   <li>{@link #getType()} - 类型标识，用于 Connection 关联与注册表查找</li>
 *   <li>{@link #describe()} - 元数据（标签/图标/操作清单/配置 schema），驱动前端物料与表单渲染</li>
 *   <li>{@link #testConnection(Connection)} - 连接测试，连接管理页"测试连接"按钮调用</li>
 *   <li>{@link #invoke(Connection, String, Map)} - 通用调用逃生舱，任何连接器无需改平台即可用</li>
 * </ol>
 *
 * <p>生命周期：不新建监听接口，用三个 default 方法，谁需要谁覆写。
 *
 * @author databus
 */
public interface Connector {

    /**
     * 连接器类型标识，全局唯一。命名允许中划线（如 {@code jdbc-postgresql}），
     * 与 {@link ConnectorManifest#getType()} 一致。
     */
    String getType();

    /**
     * 连接器元数据。驱动前端 cmp-defs 物料自动生成与连接管理页表单动态渲染。
     */
    ConnectorDescriptor describe();

    /**
     * 本连接器运行所依赖的 JDBC 驱动类全限定名（如 {@code org.postgresql.Driver}）。
     * 不需要驱动的连接器（HTTP、MQ 等）返回 {@code null} 或空串即可，不必覆写。
     *
     * <p>平台用它做两件事，两件事都不能靠「扫包里的 {@code META-INF/services/java.sql.Driver}」代替：
     * <ol>
     *   <li><b>装载校验</b>：{@code PluginManager} 实例化连接器后拿这个名字去包里加载，
     *       要求必须由包内 loader 提供。包漏打驱动 jar 时当场判装载失败，
     *       而不是等建连时静默用上平台那份（版本随平台走、卸载清不掉）。</li>
     *   <li><b>健康态</b>：连接器已装载时，用它判断驱动是包内提供还是落到了平台，
     *       据此把 def 行标成 OK / MISSING_DRIVER。</li>
     * </ol>
     *
     * <p>SPI 声明文件在驱动 jar 内部，{@code lib/} 下没有驱动 jar 时那份声明也一起消失，
     * 扫出来是空集，等于「没有需求就没有缺口」，校验形同放行——所以判据必须是连接器自己报出来的这一条。
     *
     * @return 驱动类全限定名；不需要驱动时返回 {@code null}
     */
    default String requiredDriverClass() {
        return null;
    }

    /**
     * 测试连接是否可用。连接管理页"测试连接"按钮调用。
     *
     * @param connection 连接实例配置
     * @return 测试结果（成功时返回归一化信息，失败抛 {@link ConnectorException}）
     */
    Object testConnection(Connection connection);

    /**
     * 通用调用逃生舱。operation 与 params 由 {@link ConnectorDescriptor#getOperations()} 声明，
     * 运行时按 operation 分发。保证任何连接器不改平台就能被脚本调用。
     *
     * @param connection 连接实例
     * @param operation  操作名（与 describe().operations[].name 对应）
     * @param params     操作入参（key 与 describe().operations[].params 对应）
     * @return 操作结果（结构由具体连接器定义）
     */
    default Object invoke(Connection connection, String operation, Map<String, Object> params) {
        throw new ConnectorException("OPERATION_NOT_SUPPORTED",
            "连接器 " + getType() + " 未实现 operation=" + operation);
    }

    /**
     * 连接配置变更回调：平台在连接增删改后广播。有连接池的连接器借此关掉单个连接的池。
     */
    default void onConnectionChanged(String connectionId) {}

    /**
     * 停用回调：def 行被停用（suspended）时广播。有池的连接器关闭本类型全部池。
     */
    default void onSuspend() {}

    /**
     * 卸载回调：连接器包被卸载（或应用关闭）时在<b>包内</b>执行。
     * 包内自清扫驱动注册（DriverManager 等）等全局副作用，见方案 §4.3。
     */
    default void onShutdown() {}
}