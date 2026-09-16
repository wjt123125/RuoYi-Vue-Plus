package org.dromara.databus.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据总线 JSON 编解码工具，基于 Jackson（RuoYi-Vue-Plus 默认 JSON 库）。
 * <p>
 * 仅作为内部辅助，避免在 context 层直接耦合 hutool / fastjson。
 *
 * @author databus
 */
@Slf4j
public final class JsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCodec() {
    }

    /**
     * 把对象序列化为 JSON 字符串。序列化失败时返回 {@code "null"} 并记录日志，不抛异常。
     */
    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("JSON 序列化失败: {}", e.getMessage());
            return "null";
        }
    }

    /**
     * 把 JSON 字符串解析为对象。解析失败时返回 {@code null}。
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }
}
