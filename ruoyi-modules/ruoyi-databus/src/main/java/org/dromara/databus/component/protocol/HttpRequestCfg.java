package org.dromara.databus.component.protocol;

import lombok.Data;

import java.util.Map;

/**
 * HTTP 请求组件（httpRequest）的节点参数。
 * <pre>
 * {
 *   "method": "GET",
 *   "url": "http://localhost:8080/auth/code",
 *   "headers": { "Authorization": "$.token" },
 *   "query":   { "userId": "$.userId" },
 *   "body":    { "code": "$.code" },
 *   "mappings": { "code": "$.code" }
 * }
 * </pre>
 * 本档边界：仅 GET/POST；不做超时/代理/证书/文件上传/URL 编码；
 * 非 JSON 响应整体以原始字符串存入 response。
 *
 * @author databus
 */
@Data
public class HttpRequestCfg {

    /**
     * 请求方法：GET / POST（缺省 GET）。
     */
    private String method;

    /**
     * 请求地址，支持裸路径片段拼接（如 {@code http://x/$.userId/detail}）。
     */
    private String url;

    /**
     * 请求头：value 支持字面量 / 裸路径 / 混合字符串。
     */
    private Map<String, Object> headers;

    /**
     * 查询参数：value 同上，发送前拼到 URL query string。
     */
    private Map<String, Object> query;

    /**
     * 请求体：Map/List 按 JSON 发送（内部字符串值先做路径解析）；字符串直接发送。
     */
    private Object body;

    /**
     * 响应字段抽取：key = 存入数据空间的字段名，value = 响应体内的 JSONPath。
     * 响应整体始终存于 {@code $.<dataSpace>.response}。
     */
    private Map<String, String> mappings;
}
