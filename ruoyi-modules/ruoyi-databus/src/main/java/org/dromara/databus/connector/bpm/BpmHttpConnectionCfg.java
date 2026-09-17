package org.dromara.databus.connector.bpm;

import lombok.Data;

import java.util.List;

/**
 * BPM HTTP 连接实例的强类型配置（{@link org.dromara.databus.connector.Connection#getConfig()} 的反序列化目标）。
 *
 * <p>对应 {@link org.dromara.databus.connector.ConnectorDescriptor#getConfigSchema()} 中 bpmHttp 的字段：
 * <ul>
 *   <li>{@code endpoint} - BPM 容器地址（如 {@code http://localhost:8088}），必填</li>
 *   <li>{@code authUser} - BPM 默认用户（连接级默认 uid，组件层未配 uid 时用此值），可选</li>
 *   <li>{@code authPassword} - BPM 默认密码，可选</li>
 *   <li>{@code timeoutMs} - HTTP 调用超时（毫秒），默认 30000</li>
 *   <li>{@code retryCount} - 失败重试次数，默认 0（1D-P0 不实现重试，保留字段供未来）</li>
 *   <li>{@code ipWhiteList} - IP 白名单（决策 9.1.7：由总线层从 Connection 配置传入请求体）</li>
 * </ul>
 *
 * <p>由 {@link BpmHttpConnector#fromConnection} 从 Connection.config 反序列化得到。
 *
 * @author databus
 */
@Data
public class BpmHttpConnectionCfg {

    /** BPM 容器地址（不含末尾斜杠），如 http://localhost:8088 */
    private String endpoint;

    /** BPM 默认用户（连接级默认 uid，组件层未配 uid 时用此值），可选 */
    private String authUser;

    /** BPM 默认密码，可选 */
    private String authPassword;

    /** HTTP 调用超时（毫秒），默认 30000 */
    private Integer timeoutMs = 30000;

    /** 失败重试次数，默认 0（1D-P0 不实现重试，保留字段） */
    private Integer retryCount = 0;

    /** IP 白名单（决策 9.1.7：由总线层从 Connection 配置传入请求体） */
    private List<String> ipWhiteList;
}
