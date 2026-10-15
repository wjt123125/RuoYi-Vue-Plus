package org.dromara.databus.alert;

import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.enums.AlertNotifyStatusEnum;
import org.dromara.databus.enums.AlertNotifyTypeEnum;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Webhook 通知渠道：把 {@link AlertEvent} 原样序列化为 JSON POST 到外部地址。
 * <p>
 * 渠道参数：{@code {"url":"https://...","secret":"可选签名密钥"}}。配置了 secret 时
 * 请求头 {@code X-Databus-Signature} = HMAC-SHA256(body, secret) 的十六进制串，
 * 接收方可用同一密钥验签防伪造。连接超时 5s、读超时 10s，HTTP 非 2xx 抛异常由路由记 FAILED。
 *
 * @author databus
 */
@Slf4j
@Component
public class WebhookAlertNotifier implements AlertNotifier {

    /**
     * 签名请求头名。
     */
    private static final String SIGNATURE_HEADER = "X-Databus-Signature";

    /**
     * 独立 RestClient：连接 5s / 读 10s，与业务 HTTP 资源隔离。
     */
    private final RestClient restClient;

    public WebhookAlertNotifier() {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public AlertNotifyTypeEnum type() {
        return AlertNotifyTypeEnum.WEBHOOK;
    }

    @Override
    public String send(AlertEvent event, String configJson) throws Exception {
        Dict config = StringUtils.isBlank(configJson) ? null : JsonUtils.parseMap(configJson);
        if (config == null) {
            throw new IllegalArgumentException("Webhook 渠道缺少配置");
        }
        String url = config.getStr("url");
        if (StringUtils.isBlank(url)) {
            throw new IllegalArgumentException("Webhook 渠道缺少 url 参数");
        }
        String secret = config.getStr("secret");
        String body = JsonUtils.toJsonString(event);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.isNotBlank(secret)) {
            headers.set(SIGNATURE_HEADER, hmacSha256Hex(body, secret));
        }
        restClient.post()
            .uri(url)
            .headers(httpHeaders -> httpHeaders.addAll(headers))
            .body(body)
            .retrieve()
            .toBodilessEntity();
        return AlertNotifyStatusEnum.SUCCESS.getCode();
    }

    /**
     * HMAC-SHA256 十六进制签名（接收方按 body 原文验签）。
     */
    private String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

}
