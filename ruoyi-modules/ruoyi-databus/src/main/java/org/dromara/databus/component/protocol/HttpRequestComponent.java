package org.dromara.databus.component.protocol;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.context.JsonCodec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 请求组件（注册名 {@code httpRequest}）。
 * <p>
 * 单组件承载全部通用 HTTP 能力（method/bodyType 参数化，不按动词或媒介拆件），
 * 完整规格见 docs/wiki/databus-http-component.md：
 * <ul>
 *   <li>method：GET/POST/PUT/PATCH/DELETE；bodyType：none/json/form/raw（策略类序列化）；</li>
 *   <li>query 支持同名多值，headers/query/body/auth 值支持字面量/裸路径/混合字符串解析；</li>
 *   <li>auth：none/basic（自动 Base64）/bearer（自动拼前缀）；</li>
 *   <li>出口：固定写 {@code $.<tag>.status} 与 {@code $.<tag>.response}，
 *       响应头按白名单写 {@code $.<tag>.headers.*}，mappings 默认 required；</li>
 *   <li>4xx/5xx 默认抛带全量上下文的 ServiceException，failOnHttpError=false 可容错落盘；</li>
 *   <li>timeoutMs 单值（默认 10s），RestClient 按超时分键缓存，默认跟随 3xx；不做重试。</li>
 * </ul>
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("httpRequest")
public class HttpRequestComponent extends DatabusNodeComponent {

    private static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private static final int LOG_EXCERPT_LEN = 500;
    private static final Set<String> SUPPORTED_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> SUPPORTED_BODY_TYPES = Set.of("none", "json", "form", "raw");
    private static final Set<String> RESERVED_FIELDS = Set.of("status", "response", "headers");
    private static final Set<String> SUPPORTED_AUTH = Set.of("none", "basic", "bearer");

    /**
     * 按 timeoutMs 分键缓存 RestClient——HttpClient 建造重且带连接池/线程，不可每请求新建。
     */
    private static final Map<Long, RestClient> CLIENT_CACHE = new ConcurrentHashMap<>();

    private static final Map<String, HttpBodyStrategy> BODY_STRATEGIES = Map.of(
        "json", new JsonBodyStrategy(),
        "form", new FormBodyStrategy(),
        "raw", new RawBodyStrategy()
    );

    /** 日志脱敏：key 命中即掩码（query 正则同步使用）。 */
    private static final Pattern SENSITIVE_KEY_PATTERN =
        Pattern.compile("(?i).*(password|passwd|token|secret|authorization|authCode|apiKey).*");
    private static final Pattern SENSITIVE_QUERY_PATTERN =
        Pattern.compile("(?i)([?&](?:password|passwd|token|secret|apikey|api_key)=)[^&]*");

    @Override
    public void process() {
        HttpRequestCfg cfg = this.getCmpData(HttpRequestCfg.class);
        String tag = this.getTag();
        if (cfg == null || !StringUtils.hasText(cfg.getUrl())) {
            throw new ServiceException("HTTP 组件缺少 url 配置（tag=" + tag + "）");
        }
        if (!StringUtils.hasText(tag)) {
            throw new ServiceException("HTTP 组件缺少数据空间标识（tag）");
        }
        DatabusContext ctx = getDatabusContext();

        String method = normalize(cfg.getMethod(), "GET").toUpperCase(Locale.ROOT);
        if (!SUPPORTED_METHODS.contains(method)) {
            throw new ServiceException("HTTP 组件不支持的 method: " + method + "（tag=" + tag + "）");
        }
        String bodyType = normalize(cfg.getBodyType(), "none").toLowerCase(Locale.ROOT);
        if (!SUPPORTED_BODY_TYPES.contains(bodyType)) {
            throw new ServiceException("HTTP 组件不支持的 bodyType: " + bodyType + "（tag=" + tag + "）");
        }
        long timeoutMs = resolveTimeout(cfg.getTimeoutMs(), tag);
        Charset explicitCharset = resolveExplicitCharset(cfg.getResponseCharset(), tag);
        boolean failOnHttpError = !Boolean.FALSE.equals(cfg.getFailOnHttpError());

        String url = String.valueOf(ctx.resolve(cfg.getUrl()));
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new ServiceException("HTTP 组件 url 必须以 http:// 或 https:// 开头: " + url + "（tag=" + tag + "）");
        }
        URI uri = buildUri(url, cfg.getQuery(), ctx, tag);

        Map<String, String> headers = resolveHeaders(cfg.getHeaders(), ctx, tag);
        applyAuth(headers, cfg.getAuth(), ctx, tag);

        RestClient client = getClient(timeoutMs);
        RestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(method)).uri(uri);
        headers.forEach(request::header);

        if ("none".equals(bodyType)) {
            if (cfg.getBody() != null) {
                log.warn("[databus] httpRequest bodyType=none，已配置的 body 将被忽略（tag={}）", tag);
            }
        } else {
            Object resolvedBody = resolveDeep(cfg.getBody(), ctx);
            HttpBodyStrategy.PreparedBody prepared =
                BODY_STRATEGIES.get(bodyType).prepare(resolvedBody, cfg.getRawContentType(), tag);
            request.contentType(prepared.contentType()).body(prepared.content());
            // raw 体可能含任意敏感文本，不打原文只记长度；json/form 走 key 脱敏
            Object logBody = "raw".equals(bodyType)
                ? "[raw 长度 " + (resolvedBody instanceof String s ? s.length() : 0) + "]"
                : maskForLog(resolvedBody);
            log.info("[databus] httpRequest 请求: {} {} body={}", method, maskUrl(url), excerpt(logBody));
        }

        long start = System.currentTimeMillis();
        HttpResult result;
        try {
            result = request.exchange((req, res) -> {
                byte[] bytes = res.getBody() != null ? StreamUtils.copyToByteArray(res.getBody()) : new byte[0];
                Charset charset = explicitCharset != null
                    ? explicitCharset
                    : resolveResponseCharset(res);
                return new HttpResult(res.getStatusCode().value(), bytes, pickHeaders(cfg.getResponseHeaders(), res), charset);
            });
        } catch (ResourceAccessException e) {
            String reason = e.getMostSpecificCause() != null && e.getMostSpecificCause().getMessage() != null
                ? e.getMostSpecificCause().getMessage()
                : e.getClass().getSimpleName();
            throw new ServiceException("HTTP 请求网络异常: " + method + " " + maskUrl(url)
                + "（tag=" + tag + "，原因: " + reason + "）", e);
        }
        long elapsed = System.currentTimeMillis() - start;

        String text = result.body().length == 0 ? null : new String(result.body(), result.charset());
        if (result.status() >= 400) {
            String bodyExcerpt = text == null ? "<空响应体>" : excerpt(text);
            if (failOnHttpError) {
                log.warn("[databus] httpRequest 错误响应: {} {} status={} 耗时={}ms body={}",
                    method, maskUrl(url), result.status(), elapsed, bodyExcerpt);
                throw new ServiceException("HTTP 请求返回错误状态: " + method + " " + maskUrl(url)
                    + " → " + result.status() + "（tag=" + tag + "），响应体: " + bodyExcerpt);
            }
            log.warn("[databus] httpRequest 容错模式落盘错误响应: {} {} status={} 耗时={}ms",
                method, maskUrl(url), result.status(), elapsed);
        }

        Object parsed = JsonCodec.parse(text);
        Object responseStore = text == null ? null : (parsed != null ? parsed : text);

        save("$." + tag + ".status", result.status());
        save("$." + tag + ".response", responseStore);
        if (cfg.getResponseHeaders() != null && !cfg.getResponseHeaders().isEmpty()) {
            save("$." + tag + ".headers", result.headers());
        }
        extractMappings(cfg.getMappings(), responseStore, tag);

        // 摘要只报方法+状态码：成败、耗时表格列已有；响应字节数无行动意义，响应内容看抽屉快照
        resultSummary(method + " " + result.status());

        log.info("[databus] httpRequest 响应: {} {} status={} 耗时={}ms body={}",
            method, maskUrl(url), result.status(), elapsed,
            responseStore == null ? "<空响应体>" : excerpt(maskForLog(responseStore)));
    }

    // ── 配置解析 ──

    private static String normalize(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private static long resolveTimeout(Long timeoutMs, String tag) {
        if (timeoutMs == null) {
            return DEFAULT_TIMEOUT_MS;
        }
        if (timeoutMs <= 0) {
            throw new ServiceException("HTTP 组件 timeoutMs 必须为正数: " + timeoutMs + "（tag=" + tag + "）");
        }
        return timeoutMs;
    }

    private static Charset resolveExplicitCharset(String charsetName, String tag) {
        if (!StringUtils.hasText(charsetName)) {
            return null;
        }
        try {
            return Charset.forName(charsetName.trim());
        } catch (Exception e) {
            throw new ServiceException("HTTP 组件 responseCharset 非法: " + charsetName + "（tag=" + tag + "）", e);
        }
    }

    private static Charset resolveResponseCharset(ClientHttpResponse res) {
        MediaType contentType = res.getHeaders().getContentType();
        if (contentType != null && contentType.getCharset() != null) {
            return contentType.getCharset();
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * query 拼接收尾：Collection/数组 → 同名重复参数；对象值不可表达（配置错）；null 跳过。
     */
    private URI buildUri(String rawUrl, Map<String, Object> query, DatabusContext ctx, String tag) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(rawUrl);
        if (query != null) {
            query.forEach((key, value) -> {
                Object resolved = value == null ? null : ctx.resolve(value);
                if (resolved == null) {
                    return;
                }
                if (resolved instanceof Map<?, ?>) {
                    throw new ServiceException(
                        "HTTP 组件 query 参数 " + key + " 的值不能是对象（tag=" + tag + "）");
                }
                if (resolved instanceof Collection<?> collection) {
                    Object[] items = collection.stream().filter(item -> item != null).toArray();
                    if (items.length > 0) {
                        builder.queryParam(key, items);
                    }
                } else if (resolved.getClass().isArray()) {
                    builder.queryParam(key, (Object[]) resolved);
                } else {
                    builder.queryParam(key, resolved);
                }
            });
        }
        try {
            return builder.build().encode(StandardCharsets.UTF_8).toUri();
        } catch (IllegalArgumentException e) {
            throw new ServiceException("HTTP 组件 URL 非法: " + rawUrl + "（tag=" + tag + "）", e);
        }
    }

    /**
     * headers 全量解析：Collection 值逗号拼接，对象值配置错，null 跳过。
     */
    private Map<String, String> resolveHeaders(Map<String, Object> source, DatabusContext ctx, String tag) {
        Map<String, String> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((key, value) -> {
            Object resolved = value == null ? null : ctx.resolve(value);
            if (resolved == null) {
                return;
            }
            if (resolved instanceof Map<?, ?>) {
                throw new ServiceException(
                    "HTTP 组件 header " + key + " 的值不能是对象（tag=" + tag + "）");
            }
            if (resolved instanceof Collection<?> collection) {
                List<String> parts = new ArrayList<>(collection.size());
                collection.forEach(item -> {
                    if (item != null) {
                        parts.add(String.valueOf(item));
                    }
                });
                result.put(key, String.join(", ", parts));
            } else if (resolved.getClass().isArray()) {
                List<String> parts = new ArrayList<>();
                for (Object item : (Object[]) resolved) {
                    if (item != null) {
                        parts.add(String.valueOf(item));
                    }
                }
                result.put(key, String.join(", ", parts));
            } else {
                result.put(key, String.valueOf(resolved));
            }
        });
        return result;
    }

    /**
     * 应用鉴权：auth 与 headers 同名 Authorization 冲突时 auth 为准并 warn。
     */
    private void applyAuth(Map<String, String> headers, HttpRequestCfg.AuthCfg auth, DatabusContext ctx, String tag) {
        String type = auth == null || !StringUtils.hasText(auth.getType())
            ? "none"
            : auth.getType().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_AUTH.contains(type)) {
            throw new ServiceException("HTTP 组件 auth.type 非法: " + type + "（tag=" + tag + "），可选 none/basic/bearer");
        }
        if ("none".equals(type)) {
            return;
        }
        headers.keySet().removeIf(name -> {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                log.warn("[databus] httpRequest 同时配置了 auth 与 headers.Authorization，以 auth 为准（tag={}）", tag);
                return true;
            }
            return false;
        });
        if ("bearer".equals(type)) {
            Object token = ctx.resolve(auth.getToken());
            if (token == null || !StringUtils.hasText(String.valueOf(token))) {
                throw new ServiceException("HTTP 组件 auth.type=bearer 但 token 为空（tag=" + tag + "）");
            }
            headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        } else {
            Object username = ctx.resolve(auth.getUsername());
            Object password = ctx.resolve(auth.getPassword());
            if (username == null || password == null) {
                throw new ServiceException("HTTP 组件 auth.type=basic 但 username/password 为空（tag=" + tag + "）");
            }
            String credentials = username + ":" + password;
            headers.put(HttpHeaders.AUTHORIZATION,
                "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * 递归解析 Map/List 内的字符串值（body 用）；Map/List 原样保持结构。
     */
    private Object resolveDeep(Object value, DatabusContext ctx) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            map.forEach((key, item) -> result.put(String.valueOf(key), resolveDeep(item, ctx)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(resolveDeep(item, ctx));
            }
            return result;
        }
        return value == null ? null : ctx.resolve(value);
    }

    /**
     * 响应头白名单抽取（大小写不敏感匹配，落盘 key 统一小写）。
     */
    private static Map<String, String> pickHeaders(List<String> whitelist, ClientHttpResponse res) {
        Map<String, String> picked = new LinkedHashMap<>();
        if (whitelist == null || whitelist.isEmpty()) {
            return picked;
        }
        for (String name : whitelist) {
            if (StringUtils.hasText(name)) {
                String value = res.getHeaders().getFirst(name);
                if (value != null) {
                    picked.put(name.trim().toLowerCase(Locale.ROOT), value);
                }
            }
        }
        return picked;
    }

    /**
     * mappings 抽取：required 缺省 true，取不到/响应体为空直接抛错；field 禁用保留名。
     */
    private void extractMappings(List<HttpRequestCfg.MappingCfg> mappings, Object responseStore, String tag) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        DatabusContext responseCtx = DatabusContext.fromObject(responseStore);
        for (HttpRequestCfg.MappingCfg mapping : mappings) {
            String field = mapping.getField();
            String path = mapping.getPath();
            if (!StringUtils.hasText(field) || !StringUtils.hasText(path)) {
                throw new ServiceException("HTTP 组件 mappings 每项必须配置 field 和 path（tag=" + tag + "）");
            }
            field = field.trim();
            if (field.startsWith("$")) {
                throw new ServiceException("HTTP 组件 mappings.field 只写字段名不要带 $. 前缀: " + field + "（tag=" + tag + "）");
            }
            String firstSegment = field.contains(".") ? field.substring(0, field.indexOf('.')) : field;
            if (RESERVED_FIELDS.contains(firstSegment)) {
                throw new ServiceException("HTTP 组件 mappings.field 不能使用保留名 status/response/headers: "
                    + field + "（tag=" + tag + "）");
            }
            boolean required = !Boolean.FALSE.equals(mapping.getRequired());
            if (responseStore == null) {
                if (required) {
                    throw new ServiceException("HTTP 响应体为空，无法抽取字段 " + field
                        + "（tag=" + tag + "，path=" + path + "）；非必须字段可配 required:false");
                }
                continue;
            }
            Object value = responseCtx.readOptional(path);
            if (value == null && required) {
                throw new ServiceException("HTTP 响应缺少字段（路径不存在或值为 null）: path=" + path
                    + " → field=" + field + "（tag=" + tag + "）；非必须字段可配 required:false。实际响应: "
                    + excerpt(maskForLog(responseStore)));
            }
            save("$." + tag + "." + field, value);
        }
    }

    // ── 基础设施 ──

    /**
     * 构造带超时与重定向跟随的 RestClient（JDK HttpClient：默认不跟随 3xx，显式开 NORMAL）。
     */
    private static RestClient getClient(long timeoutMs) {
        return CLIENT_CACHE.computeIfAbsent(timeoutMs, ms -> {
            HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(ms))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofMillis(ms));
            return RestClient.builder().requestFactory(factory).build();
        });
    }

    private static String maskUrl(String url) {
        Matcher matcher = SENSITIVE_QUERY_PATTERN.matcher(url);
        return matcher.replaceAll("$1***");
    }

    /**
     * 日志脱敏：Map/List 递归把敏感 key 的值掩码，标量原样。
     */
    private static Object maskForLog(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> masked = new LinkedHashMap<>(map.size());
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                if (SENSITIVE_KEY_PATTERN.matcher(name).matches()) {
                    masked.put(name, "***");
                } else {
                    masked.put(name, maskForLog(item));
                }
            });
            return masked;
        }
        if (value instanceof List<?> list) {
            List<Object> masked = new ArrayList<>(list.size());
            list.forEach(item -> masked.add(maskForLog(item)));
            return masked;
        }
        return value;
    }

    private static String excerpt(Object value) {
        String text = value instanceof String s ? s : JsonCodec.toJson(value);
        return text.length() <= LOG_EXCERPT_LEN ? text : text.substring(0, LOG_EXCERPT_LEN) + "...(截断)";
    }

    /**
     * 一次 HTTP 调用的原始结果（exchange 内构造，charset 随响应头确定）。
     */
    private record HttpResult(int status, byte[] body, Map<String, String> headers, Charset charset) {
    }
}
