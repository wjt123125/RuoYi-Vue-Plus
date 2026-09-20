package org.dromara.databus.connector.bpm;

import lombok.Data;

/**
 * BPM HTTP 连接实例的强类型配置（{@link org.dromara.databus.connector.Connection#getConfig()} 的反序列化目标）。
 *
 * <p>对应 {@link org.dromara.databus.connector.ConnectorDescriptor#getConfigSchema()} 中 bpmHttp 的字段：
 * <ul>
 *   <li>{@code endpoint} - BPM 容器地址（如 {@code http://localhost:8088}），必填</li>
 *   <li>{@code accessKey} - OpenAPI 访问凭证（BPM CC 身份策略 access_key），必填</li>
 *   <li>{@code apiSecret} - OpenAPI 私钥（CC 身份策略 secret，敏感字段进 credentials），必填</li>
 *   <li>{@code timeoutMs} - HTTP 调用超时（毫秒），默认 30000</li>
 *   <li>{@code retryCount} - 失败重试次数，默认 0（1D-P0 不实现重试，保留字段供未来）</li>
 * </ul>
 *
 * <p>鉴权：2026-09-20 起唯一通道为平台 {@code /portal/openapi} 签名网关
 * （access_key + HmacMD5，见 {@link BpmOpenApiSigner}），旧 /portal/r/jd 免会话通道已删除。
 *
 * <p>由 {@link BpmHttpConnector#fromConnection} 从 Connection.config 反序列化得到。
 *
 * @author databus
 */
@Data
public class BpmHttpConnectionCfg {

    /** BPM 容器地址（不含末尾斜杠），如 http://localhost:8088 */
    private String endpoint;

    /** OpenAPI 访问凭证 access_key（BPM CC 身份策略），必填 */
    private String accessKey;

    /** OpenAPI 私钥 secret（敏感字段，落库进 credentials 加密列），必填 */
    private String apiSecret;

    /** HTTP 调用超时（毫秒），默认 30000 */
    private Integer timeoutMs = 30000;

    /** 失败重试次数，默认 0（1D-P0 不实现重试，保留字段） */
    private Integer retryCount = 0;
}
