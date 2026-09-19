package org.dromara.databus.component.protocol;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * HTTP 请求组件（httpRequest）的节点参数。
 * <pre>
 * {
 *   "method": "POST",
 *   "url": "http://localhost:8080/auth/login",
 *   "headers": { "X-Tenant": "default" },
 *   "query":   { "ids": ["$.id1", "$.id2"] },
 *   "bodyType": "json",
 *   "body":    { "username": "admin", "password": "$.pwd" },
 *   "rawContentType": "text/plain",
 *   "auth":    { "type": "bearer", "token": "$.login.token" },
 *   "timeoutMs": 10000,
 *   "failOnHttpError": true,
 *   "responseCharset": "UTF-8",
 *   "responseHeaders": ["X-Total-Count"],
 *   "mappings": [
 *     { "field": "bizCode", "path": "$.code", "required": true }
 *   ]
 * }
 * </pre>
 * 完整规格见 docs/wiki/databus-http-component.md：
 * method 支持 GET/POST/PUT/PATCH/DELETE；bodyType 支持 none/json/form/raw；
 * 不做 multipart/重试/代理/证书/OAuth2/HttpConnection。
 *
 * @author databus
 */
@Data
public class HttpRequestCfg {

    /**
     * 请求方法：GET/POST/PUT/PATCH/DELETE（缺省 GET）。
     */
    private String method;

    /**
     * 请求地址（必填），必须带 http(s) 协议前缀；支持混合片段（如 {@code http://x/$.userId/detail}）。
     */
    private String url;

    /**
     * 请求头：value 支持字面量 / 裸路径 / 混合字符串；Collection 值按逗号拼接。
     * 与 {@link #auth} 生成的 Authorization 冲突时以 auth 为准（warn 并忽略此项）。
     */
    private Map<String, Object> headers;

    /**
     * 查询参数：value 同上；Collection/数组值序列化为同名重复参数（ids=1&amp;ids=2），
     * 对象值无法表达（配置错），null 值跳过。
     */
    private Map<String, Object> query;

    /**
     * 请求体媒介：none（缺省）/ json / form / raw。
     */
    private String bodyType;

    /**
     * 请求体：内部字符串值发送前做路径解析（递归）。
     * json：Map/List 序列化，字符串视为 JSON 原文；form：必须 Map，转 urlencoded；raw：必须字符串。
     */
    private Object body;

    /**
     * raw 媒介的 Content-Type（可含 charset），为空兜底 text/plain;charset=UTF-8。
     */
    private String rawContentType;

    /**
     * 鉴权配置（缺省 type=none）。
     */
    private AuthCfg auth;

    /**
     * 超时毫秒数，连接+读取合并控制（缺省 10000，必须为正数）。
     */
    private Long timeoutMs;

    /**
     * 4xx/5xx 是否直接抛错（缺省 true）；false 时错误响应也落数据空间交下游 condition 判断。
     */
    private Boolean failOnHttpError;

    /**
     * 强制响应字符集（如 GBK）；缺省按响应头 charset、再缺省 UTF-8。
     */
    private String responseCharset;

    /**
     * 响应头白名单：点名的响应头抽到 {@code $.<tag>.headers.<名小写>}，缺省空数组不落任何头。
     */
    private List<String> responseHeaders;

    /**
     * 响应字段抽取：field=数据空间字段名，path=响应体 JSONPath，required 缺省 true。
     */
    private List<MappingCfg> mappings;

    /**
     * 鉴权配置。
     */
    @Data
    public static class AuthCfg {

        /** none（缺省）/ basic / bearer。 */
        private String type;

        /** basic 用户名（支持路径取值）。 */
        private Object username;

        /** basic 密码（支持路径取值），组件自动 Base64。 */
        private Object password;

        /** bearer 令牌（支持路径取值），组件自动拼 "Bearer " 前缀。 */
        private Object token;
    }

    /**
     * 响应字段抽取配置。
     */
    @Data
    public static class MappingCfg {

        /** 存入 {@code $.<tag>.} 下的字段名；禁止 status/response/headers 保留名。 */
        private String field;

        /** 响应体内的 JSONPath（裸路径）。 */
        private String path;

        /** 取不到时是否抛错（缺省 true）。 */
        private Boolean required;
    }
}
