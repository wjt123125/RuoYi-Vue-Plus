package org.dromara.databus.component.protocol;

import org.dromara.common.core.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Collection;
import java.util.Map;

/**
 * 表单请求体策略（bodyType=form）。
 * <p>body 必须是 Map，转为 application/x-www-form-urlencoded；Collection 值展开为同名字段，
 * 标量 toString，null 跳过；非 Map（含字符串/List）报配置错。
 *
 * @author databus
 */
class FormBodyStrategy implements HttpBodyStrategy {

    @Override
    public String type() {
        return "form";
    }

    @Override
    public PreparedBody prepare(Object resolvedBody, String rawContentType, String tag) {
        if (!(resolvedBody instanceof Map<?, ?> map)) {
            throw new ServiceException(
                "HTTP 组件 bodyType=form 时 body 必须是键值对对象（tag=" + tag + "），如改用 raw 文本请切换 bodyType");
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>(map.size());
        map.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            String name = String.valueOf(key);
            if (value instanceof Collection<?> collection) {
                collection.forEach(item -> {
                    if (item != null) {
                        form.add(name, String.valueOf(item));
                    }
                });
            } else if (value.getClass().isArray()) {
                for (Object item : (Object[]) value) {
                    if (item != null) {
                        form.add(name, String.valueOf(item));
                    }
                }
            } else {
                form.add(name, String.valueOf(value));
            }
        });
        return new PreparedBody(form, MediaType.APPLICATION_FORM_URLENCODED);
    }
}
