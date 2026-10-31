package org.dromara.databus.connector.bpm;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 连接器本地 JSON 工具（Jackson 3），语义对齐平台 JsonCodec 的 toJson / parse / convertValue。
 *
 * <p>连接器不引平台业务类（方案 §2.1.2）：序列化由连接器自理；Jackson 3（tools.jackson）
 * 由父加载器提供，pom 中 scope=provided 不进连接器包。
 *
 * @author databus
 */
public final class Json {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private Json() {
    }

    /** 序列化对象为 JSON 字符串；null 转 "null"，失败返回 "null"。 */
    public static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            return "null";
        }
    }

    /** JSON 字符串解析为对象；空串或失败返回 null。 */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** 把任意对象（通常 Map / POJO）转换为指定类型对象。 */
    public static <T> T convertValue(Object fromValue, Class<T> toType) {
        return MAPPER.convertValue(fromValue, toType);
    }
}