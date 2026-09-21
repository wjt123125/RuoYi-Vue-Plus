package org.dromara.databus.connector.bpm;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS PaaS /portal/openapi 网关签名器（方案 B）。
 *
 * <p>协议来源：官方《AWS PaaS API Guide · 签名URL请求》（docs.awspaas.com，2024-11-27 版）
 * + 平台自带 OpenApiClient（ApiUtils.makeSig）字节码逐指令核实，见
 * docs/wiki/databus-bpm-endpoint-auth.md §3.2。
 *
 * <p>公共参数：cmd / access_key / timestamp（毫秒）/ sig_method=HmacMD5 / format=json，
 * 业务参数（本连接器固定为单个 {@code payload}：请求 DTO 的 JSON 串；不用 "body" 是为避开
 * 平台 ParameterHelper type=body 验签分支，详见 docs/wiki/databus-bpm-endpoint-auth.md）。
 *
 * <p>签名算法：
 * <ol>
 *   <li>剔除值为空（null 或空串）的参数；</li>
 *   <li>参数名按 ASCII 升序；</li>
 *   <li>待签名串 = {@code secret + key1value1 + key2value2 ...}（无分隔符）；</li>
 *   <li>{@code sig = uppercase(hex(HMAC_MD5(key=secret, msg=待签名串)))}。</li>
 * </ol>
 *
 * <p>传输：POST application/x-www-form-urlencoded;charset=UTF-8，全部参数（含 sig）在 form body。
 * 平台时间窗 5 分钟，timestamp 每次调用现算，不做时钟偏移处理。
 *
 * <p>纯 JDK 实现（Mac + URLEncoder），零新依赖；无状态、线程安全、静态方法即可。
 *
 * @author databus
 */
public final class BpmOpenApiSigner {

    /** 平台固定签名算法标识（公共参数 sig_method 的值，不可改） */
    public static final String SIG_METHOD = "HmacMD5";

    /** 响应格式（公共参数 format 的值） */
    public static final String FORMAT_JSON = "json";

    /** form 业务参数名（BPM 端 @Param("payload") 绑定，JSON 字符串参与签名；不用 "body" 避开平台 type=body 验签分支） */
    public static final String PARAM_PAYLOAD = "payload";

    private static final String HMAC_MD5 = "HmacMD5";

    private BpmOpenApiSigner() {
    }

    /**
     * 组装一次 openapi 调用的完整 form 参数（公共参数 + payload + sig）。
     *
     * @param cmd        BPM 端 @Mapping 值，如 com.awspaas.databus.connector.PING
     * @param bodyJson   请求 DTO 的 JSON 串；null/空串表示该调用无业务参数（如 PING），不参与签名
     * @param accessKey  访问凭证（CC 身份策略）
     * @param secret     私钥（只参与签名，不进参数、不上链路）
     * @return 已含 sig 的参数 Map（TreeMap 仅为可读性，调用方不应依赖顺序）
     */
    public static Map<String, String> buildSignedForm(String cmd, String bodyJson,
                                                       String accessKey, String secret) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("cmd", cmd);
        params.put("access_key", accessKey);
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        params.put("sig_method", SIG_METHOD);
        params.put("format", FORMAT_JSON);
        if (bodyJson != null && !bodyJson.isEmpty()) {
            params.put(PARAM_PAYLOAD, bodyJson);
        }
        params.put("sig", sign(params, secret));
        return params;
    }

    /**
     * 按平台规则计算 sig：TreeMap 已按 key ASCII 升序，空值在此跳过。
     */
    public static String sign(Map<String, String> params, String secret) {
        StringBuilder toSign = new StringBuilder(secret);
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            toSign.append(entry.getKey()).append(value);
        }
        return hmacMd5HexUpper(secret, toSign.toString());
    }

    /**
     * 把参数 Map 编码为 application/x-www-form-urlencoded 请求体（UTF-8）。
     */
    public static String toFormUrlEncoded(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * HMAC_MD5 → 大写 hex（平台要求 32 位大写）。
     */
    private static String hmacMd5HexUpper(String secret, String content) {
        try {
            Mac mac = Mac.getInstance(HMAC_MD5);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_MD5));
            byte[] raw = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                String h = Integer.toHexString(b & 0xff).toUpperCase();
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (Exception e) {
            // HmacMD5 为 JDK 必选算法、UTF-8 必选编码，理论上不可达
            throw new IllegalStateException("HmacMD5 签名初始化失败: " + e.getMessage(), e);
        }
    }
}
