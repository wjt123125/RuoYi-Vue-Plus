package org.dromara.databus.component.protocol;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.context.JsonCodec;
import org.springframework.http.MediaType;

/**
 * JSON 请求体策略（bodyType=json）。
 * <p>Map/List/标量正常 Jackson 序列化；字符串视为 JSON 原文直发（适配从数据空间取出的
 * JSON 字符串，信任用户不二次加引号）；null 报配置错。
 *
 * @author databus
 */
class JsonBodyStrategy implements HttpBodyStrategy {

    @Override
    public String type() {
        return "json";
    }

    @Override
    public PreparedBody prepare(Object resolvedBody, String rawContentType, String tag) {
        if (resolvedBody == null) {
            throw new ServiceException("HTTP 组件 bodyType=json 但 body 未配置（tag=" + tag + "）");
        }
        String content = resolvedBody instanceof String jsonText
            ? jsonText
            : JsonCodec.toJson(resolvedBody);
        return new PreparedBody(content, MediaType.APPLICATION_JSON);
    }
}
