package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;
import java.util.Map;

/**
 * HTTP 请求组件（httpRequest）的节点参数。
 * <pre>
 * {
 *   "method": "POST",
 *   "url": "http://localhost:8080/auth/login",
 *   "headers": { "X-Tenant": "default" },
 *   "query":   { "ids": ["{{ $.id1 }}", "{{ $.id2 }}"] },
 *   "bodyType": "json",
 *   "body":    { "username": "admin", "password": "{{ $.pwd }}" },
 *   "rawContentType": "text/plain",
 *   "auth":    { "type": "bearer", "token": "{{ $.login.token }}" },
 *   "timeoutMs": 10000,
 *   "failOnHttpError": true,
 *   "responseCharset": "UTF-8",
 *   "responseHeaders": ["X-Total-Count"],
 *   "mappings": [
 *     { "field": "bizCode", "path": "{{ $.code }}", "required": true }
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
    @DatabusProp(
        label = "请求方法", widget = WidgetKind.SELECT, order = 1,
        options = {
            @DatabusProp.Option(label = "GET", value = "GET"),
            @DatabusProp.Option(label = "POST", value = "POST"),
            @DatabusProp.Option(label = "PUT", value = "PUT"),
            @DatabusProp.Option(label = "PATCH", value = "PATCH"),
            @DatabusProp.Option(label = "DELETE", value = "DELETE")
        }
    )
    private String method;

    /**
     * 请求地址（必填），必须带 http(s) 协议前缀；支持嵌入表达式（如 {@code http://x/{{ $.userId }}/detail}）。
     */
    @DatabusProp(
        label = "请求地址", required = true, exprRole = ExprRole.DATA, order = 2,
        placeholder = "https://example.com/api/{{ $.路径 }}",
        description = "必须带 http(s) 前缀；路径中可嵌 {{ $.路径 }} 表达式"
    )
    private String url;

    /**
     * 请求头：value 为字面量或 {@code {{ $.路径 }}} 表达式；Collection 值按逗号拼接。
     * 与 {@link #auth} 生成的 Authorization 冲突时以 auth 为准（warn 并忽略此项）。
     */
    @DatabusProp(label = "请求头 headers", valueExprRole = ExprRole.DATA, order = 3)
    private Map<String, Object> headers;

    /**
     * 查询参数：value 同上；Collection/数组值序列化为同名重复参数（ids=1&amp;ids=2），
     * 对象值无法表达（配置错），null 值跳过。
     */
    @DatabusProp(label = "查询参数 query", valueExprRole = ExprRole.DATA, order = 4)
    private Map<String, Object> query;

    /**
     * 请求体媒介：none（缺省）/ json / form / raw。
     */
    @DatabusProp(
        label = "请求体类型", widget = WidgetKind.SELECT, order = 5,
        options = {
            @DatabusProp.Option(label = "无请求体 none", value = "none"),
            @DatabusProp.Option(label = "JSON json", value = "json"),
            @DatabusProp.Option(label = "表单 form", value = "form"),
            @DatabusProp.Option(label = "原文 raw", value = "raw")
        }
    )
    private String bodyType;

    /**
     * 请求体：内部字符串值发送前做路径解析（递归）。
     * json：Map/List 序列化，字符串视为 JSON 原文；form：必须 Map，转 urlencoded；raw：必须字符串。
     */
    @DatabusProp(
        label = "请求体 body", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 6,
        description = "json：JSON 对象（内部字符串可写 {{ $.路径 }}）；form：键值对象；raw：字符串。复杂结构请用 JSON 高级模式",
        showWhen = @DatabusProp.ShowWhen(field = "bodyType", ne = "none")
    )
    private Object body;

    /**
     * raw 媒介的 Content-Type（可含 charset），为空兜底 text/plain;charset=UTF-8。
     */
    @DatabusProp(
        label = "raw Content-Type", order = 7, placeholder = "text/plain",
        showWhen = @DatabusProp.ShowWhen(field = "bodyType", eq = "raw")
    )
    private String rawContentType;

    /**
     * 鉴权配置（缺省 type=none）。
     */
    @DatabusProp(label = "鉴权 auth", order = 8)
    private AuthCfg auth;

    /**
     * 超时毫秒数，连接+读取合并控制（缺省 10000，必须为正数）。
     */
    @DatabusProp(label = "超时毫秒", order = 9)
    private Long timeoutMs;

    /**
     * 4xx/5xx 是否直接抛错（缺省 true）；false 时错误响应也落数据空间交下游 condition 判断。
     */
    @DatabusProp(
        label = "HTTP 错误直接抛错", widget = WidgetKind.BOOLEAN, order = 10,
        description = "关闭后 4xx/5xx 也落数据空间，交下游 condition 件判断"
    )
    private Boolean failOnHttpError;

    /**
     * 强制响应字符集（如 GBK）；缺省按响应头 charset、再缺省 UTF-8。
     */
    @DatabusProp(label = "响应字符集", order = 11, placeholder = "UTF-8")
    private String responseCharset;

    /**
     * 响应头白名单：点名的响应头抽到 {@code $.<tag>.headers.<名小写>}，缺省空数组不落任何头。
     */
    @DatabusProp(label = "响应头白名单", order = 12, description = "输入头名称后回车添加")
    private List<String> responseHeaders;

    /**
     * 响应字段抽取：field=数据空间字段名，path=响应体 JSONPath，required 缺省 true。
     */
    @DatabusProp(label = "响应字段抽取 mappings", order = 13)
    private List<MappingCfg> mappings;

    /**
     * 鉴权配置。
     */
    @Data
    public static class AuthCfg {

        /** none（缺省）/ basic / bearer。 */
        @DatabusProp(
            label = "鉴权方式", widget = WidgetKind.SELECT, order = 1,
            options = {
                @DatabusProp.Option(label = "无 none", value = "none"),
                @DatabusProp.Option(label = "Basic", value = "basic"),
                @DatabusProp.Option(label = "Bearer Token", value = "bearer")
            }
        )
        private String type;

        /** basic 用户名（支持路径取值）。 */
        @DatabusProp(
            label = "用户名", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 2,
            showWhen = @DatabusProp.ShowWhen(field = "type", eq = "basic")
        )
        private Object username;

        /** basic 密码（支持路径取值），组件自动 Base64。 */
        @DatabusProp(
            label = "密码", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 3,
            description = "可填 {{ $.路径 }} 表达式；组件自动 Base64",
            showWhen = @DatabusProp.ShowWhen(field = "type", eq = "basic")
        )
        private Object password;

        /** bearer 令牌（支持路径取值），组件自动拼 "Bearer " 前缀。 */
        @DatabusProp(
            label = "Token", widget = WidgetKind.JSON, exprRole = ExprRole.DATA, order = 4,
            description = "可填 {{ $.路径 }} 表达式；组件自动拼 Bearer 前缀",
            showWhen = @DatabusProp.ShowWhen(field = "type", eq = "bearer")
        )
        private Object token;
    }

    /**
     * 响应字段抽取配置。
     */
    @Data
    public static class MappingCfg {

        /** 存入 {@code $.<tag>.} 下的字段名；禁止 status/response/headers 保留名。 */
        @DatabusProp(label = "存为字段名", required = true, order = 1)
        private String field;

        /** 响应体内的取值表达式（{@code {{ $.code }}}）。 */
        @DatabusProp(
            label = "响应取值表达式", required = true, exprRole = ExprRole.DATA, order = 2,
            placeholder = "{{ $.code }}"
        )
        private String path;

        /** 取不到时是否抛错（缺省 true）。 */
        @DatabusProp(label = "取不到则抛错", widget = WidgetKind.BOOLEAN, order = 3)
        private Boolean required;
    }
}
