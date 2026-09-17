package org.dromara.databus.connector;

/**
 * 连接器协议层异常。
 *
 * <p>使用场景：
 * <ul>
 *   <li>连接测试失败（{@link Connector#testConnection} 抛出）</li>
 *   <li>连接器业务方法调用失败（如 {@code BpmHttpConnector#createSession} 解析 BPM 端错误码后抛出）</li>
 *   <li>Connector 注册表查找失败（无对应 type 的 Connector）</li>
 *   <li>Connection 配置缺失或类型不匹配</li>
 * </ul>
 *
 * <p>设计原则：
 * <ul>
 *   <li>承载 BPM 端错误码（{@code code} 字段），便于 Component 层按错误码翻译业务语义</li>
 *   <li>继承 {@link RuntimeException} 不强制 catch，由 LiteFlow 标记节点失败</li>
 *   <li>cause 字段保留底层异常（如 RestClient 网络异常）便于排查</li>
 * </ul>
 *
 * @author databus
 */
public class ConnectorException extends RuntimeException {

    /** BPM 端错误码（如 "BPM_SESSION_CREATE_FAILED"），协议层失败时从 BPM 端响应解析得到 */
    private final String code;

    public ConnectorException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ConnectorException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
