package org.dromara.databus.component.protocol;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 原始文本请求体策略（bodyType=raw）。
 * <p>body 必须是字符串（text/plain、裸 XML 等），Content-Type 由 rawContentType 手填，
 * 为空兜底 text/plain;charset=UTF-8；配 Map/List 报配置错并引导改用 json。
 *
 * @author databus
 */
class RawBodyStrategy implements HttpBodyStrategy {

    private static final MediaType DEFAULT_RAW_TYPE =
        new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    @Override
    public String type() {
        return "raw";
    }

    @Override
    public PreparedBody prepare(Object resolvedBody, String rawContentType, String tag) {
        if (!(resolvedBody instanceof String text)) {
            throw new ServiceException(
                "HTTP 组件 bodyType=raw 时 body 必须是字符串（tag=" + tag + "），结构化数据请改用 json");
        }
        MediaType mediaType = DEFAULT_RAW_TYPE;
        if (StringUtils.hasText(rawContentType)) {
            try {
                mediaType = MediaType.parseMediaType(rawContentType.trim());
            } catch (IllegalArgumentException e) {
                throw new ServiceException(
                    "HTTP 组件 rawContentType 非法: " + rawContentType + "（tag=" + tag + "）", e);
            }
        }
        return new PreparedBody(text, mediaType);
    }
}
