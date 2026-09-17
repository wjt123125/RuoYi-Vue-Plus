package org.dromara.databus.component.protocol;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.context.JsonCodec;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求组件（注册名 {@code httpRequest}）。
 * <p>
 * 按节点参数 {@link HttpRequestCfg} 发起 GET/POST 请求，参数中的字符串值发送前经
 * DatabusContext 解析（字面量 / 裸路径整取 / 混合片段替换）。响应体解析为 JSON 后
 * 整体存入数据空间 {@code $.<tag>.response}，并按 mappings 抽取平铺字段。
 * <p>
 * 本档边界：仅 GET/POST，不做超时/代理/证书/文件上传；非 JSON 响应以原始字符串存储；
 * 请求异常（含非 2xx）直接抛出，由 LiteFlow 标记节点失败并在试运行结果中展示。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("httpRequest")
public class HttpRequestComponent extends DatabusNodeComponent {

    /**
     * 本档用默认 RestClient（JDK HttpClient 工厂），不配置连接池/超时/拦截器。
     */
    private static final RestClient REST_CLIENT = RestClient.create();

    @Override
    public void process() {
        HttpRequestCfg cfg = this.getCmpData(HttpRequestCfg.class);
        if (cfg == null || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            throw new ServiceException("HTTP 组件缺少 url 配置（tag=" + this.getTag() + "）");
        }
        String dataSpace = this.getTag();
        if (dataSpace == null || dataSpace.isBlank()) {
            throw new ServiceException("HTTP 组件缺少数据空间标识（tag）");
        }
        DatabusContext ctx = getDatabusContext();

        String method = cfg.getMethod() == null || cfg.getMethod().isBlank() ? "GET" : cfg.getMethod().trim().toUpperCase();
        if (!"GET".equals(method) && !"POST".equals(method)) {
            throw new ServiceException("HTTP 组件本档仅支持 GET/POST，实际配置: " + method);
        }

        String url = String.valueOf(ctx.resolve(cfg.getUrl()));
        URI uri = buildUri(url, resolveToStringMap(cfg.getQuery(), ctx));
        Map<String, String> headers = resolveToStringMap(cfg.getHeaders(), ctx);

        RestClient.RequestBodySpec request = REST_CLIENT.method(HttpMethod.valueOf(method)).uri(uri);
        headers.forEach(request::header);

        if ("POST".equals(method) && cfg.getBody() != null) {
            Object resolvedBody = resolveDeep(cfg.getBody(), ctx);
            if (resolvedBody instanceof Map<?, ?> || resolvedBody instanceof List<?>) {
                request.contentType(MediaType.APPLICATION_JSON).body(JsonCodec.toJson(resolvedBody));
            } else {
                request.body(String.valueOf(resolvedBody));
            }
        }

        String responseText = request.retrieve().body(String.class);
        Object responseJson = JsonCodec.parse(responseText);
        // 非 JSON 响应存原始字符串，保证结果可见
        Object responseStore = responseJson != null ? responseJson : responseText;
        save("$." + dataSpace + ".response", responseStore);

        extractMappings(cfg.getMappings(), responseJson, dataSpace);
        log.debug("[databus] httpRequest {} {} 完成，tag={}", method, url, dataSpace);
    }

    /**
     * 拼接查询参数（本档不做 URL 编码特殊处理，值按字符串原样追加）。
     */
    private URI buildUri(String url, Map<String, String> query) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(url);
        if (query != null) {
            query.forEach((key, value) -> {
                if (value != null) {
                    builder.queryParam(key, value);
                }
            });
        }
        return builder.build().toUri();
    }

    /**
     * Map 值逐个经上下文解析并转字符串（headers/query 用）；null 值保留为 null 由调用方跳过。
     */
    private Map<String, String> resolveToStringMap(Map<String, Object> source, DatabusContext ctx) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> {
            Object resolved = value == null ? null : ctx.resolve(value);
            result.put(key, resolved == null ? null : resolved.toString());
        });
        return result;
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
     * 按 mappings 从响应 JSON 中抽取字段，平铺到 {@code $.<dataSpace>.<字段名>}。
     */
    private void extractMappings(Map<String, String> mappings, Object responseJson, String dataSpace) {
        if (mappings == null || mappings.isEmpty() || !(responseJson instanceof Map<?, ?> || responseJson instanceof List<?>)) {
            return;
        }
        DatabusContext responseCtx = DatabusContext.fromObject(responseJson);
        mappings.forEach((fieldName, jsonPath) -> {
            if (fieldName == null || jsonPath == null) {
                return;
            }
            Object value = responseCtx.readOptional(jsonPath);
            save("$." + dataSpace + "." + fieldName, value);
        });
    }
}
