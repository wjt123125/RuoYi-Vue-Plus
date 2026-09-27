package org.dromara.databus.context;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.el.bean.ChainInputParam;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 链路入参登记表校验工具（2026-09-27 拍板，见 databus-context-design.md §3.4）。
 * <p>
 * 两类校验：保存时校验登记表本身（路径以 {@code $.} 开头、不可重复）；
 * 执行前校验必填（必填路径在最终入参上取不到、或为空字符串即拦截）。
 * 默认值不参与真实执行，故本类不处理默认值注入。
 *
 * @author databus
 */
@Slf4j
public final class InputParamValidator {

    private InputParamValidator() {
    }

    /**
     * 保存时校验登记表：路径必须以 $. 开头且不可重复（空表直接放行）。
     */
    public static void validateDefs(List<ChainInputParam> params) {
        if (params == null || params.isEmpty()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ChainInputParam param : params) {
            String path = param.getPath() == null ? null : param.getPath().trim();
            if (path == null || path.isBlank() || !path.startsWith("$.")) {
                throw new ServiceException("入参登记路径必须以 $. 开头：" + param.getPath());
            }
            if (!seen.add(path)) {
                throw new ServiceException("入参登记路径重复：" + path);
            }
        }
    }

    /**
     * 执行前必填校验：必填路径在入参上取不到（PathNotFoundException）或值为空字符串即拦截。
     * <p>
     * 入参为 null 时按空文档处理——任何必填路径都会失败。
     */
    public static void validateRequired(List<ChainInputParam> params, Object requestData) {
        if (params == null || params.isEmpty()) {
            return;
        }
        DocumentContext document = JsonPath.parse(requestData == null ? "{}" : JsonCodec.toJson(requestData));
        for (ChainInputParam param : params) {
            if (!Boolean.TRUE.equals(param.getRequired())) {
                continue;
            }
            Object value;
            try {
                value = document.read(param.getPath());
            } catch (PathNotFoundException e) {
                throw new ServiceException("缺少必填入参：" + param.getPath(), e);
            }
            if (value == null || value instanceof CharSequence cs && cs.isEmpty()) {
                throw new ServiceException("必填入参不能为空：" + param.getPath());
            }
        }
    }

}
