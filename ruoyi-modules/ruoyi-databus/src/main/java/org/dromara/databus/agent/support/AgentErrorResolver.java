package org.dromara.databus.agent.support;

import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.MessageUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.enums.AgentErrorCategoryEnum;
import org.dromara.databus.agent.event.AgentError;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.UUID;

import static org.dromara.databus.enums.AgentErrorCategoryEnum.CONFIG;
import static org.dromara.databus.enums.AgentErrorCategoryEnum.PERMISSION;
import static org.dromara.databus.enums.AgentErrorCategoryEnum.TRANSIENT;
import static org.dromara.databus.enums.AgentErrorCategoryEnum.UNKNOWN;

/**
 * 用户可见错误信封的组装入口（设计选择，见 docs/agent/error-handling.md）。
 * <p>
 * 只做三件事：生成日志关联编号、按结构化字段归类、按类别从 i18n 取一句人话。
 * 不解析业务字符串、不维护“异常类型 → 文案”的映射；原始 payload/堆栈只进日志。
 * <p>
 * 第一步只按结构化数字状态码归类；等 SnailAI 的错误事件带上稳定 code/type 后，
 * 再在 {@link #classify(Dict)} 里补协议级映射。
 *
 * @author databus
 */
@Slf4j
@Component
public class AgentErrorResolver {

    /**
     * 上游错误的事件码前缀；具体后缀由类别决定
     */
    private static final String CODE_PREFIX = "UPSTREAM_";

    /**
     * 组装上游错误信封，并记录原始内容。
     *
     * @param runId run 句柄
     * @param raw   上游 error 事件的原始 data
     * @return 用户可见错误信封
     */
    public AgentError fromUpstream(String runId, String raw) {
        String referenceId = referenceId();
        log.warn("[databus-agent] 上游错误 runId={} ref={} raw={}", runId, referenceId, raw);
        Dict payload = parse(raw);
        AgentErrorCategoryEnum category = classify(payload);
        return build(CODE_PREFIX + category.name(), category, retryAfterMs(payload, category), referenceId);
    }

    /**
     * 组装本地异常信封（上游连接失败、订阅失败等），并记录堆栈。
     *
     * @param runId run 句柄
     * @param error 异常
     * @return 用户可见错误信封
     */
    public AgentError fromException(String runId, Throwable error) {
        String referenceId = referenceId();
        log.error("[databus-agent] AI 对话异常 runId={} ref={}", runId, referenceId, error);
        return build("INTERNAL", UNKNOWN, null, referenceId);
    }

    /**
     * 未识别错误（上游未给 done / 未给错误就直接结束）。
     *
     * @param runId  run 句柄
     * @param reason 内部原因，只进日志
     * @return 用户可见错误信封
     */
    public AgentError unknown(String runId, String reason) {
        String referenceId = referenceId();
        log.warn("[databus-agent] 未识别错误 runId={} ref={} reason={}", runId, referenceId, reason);
        return build("INTERNAL", UNKNOWN, null, referenceId);
    }

    private AgentError build(String code, AgentErrorCategoryEnum category, Long retryAfterMs, String referenceId) {
        boolean retryable = category == TRANSIENT;
        String message = MessageUtils.message("agent.error." + category.getCode());
        return new AgentError(code, category.getCode(), retryable, retryable ? retryAfterMs : null,
            message, referenceId);
    }

    /**
     * 第一步只认结构化数字状态码；识别不了就 UNKNOWN。
     * 不在 message 文本里做 contains 猜测，避免脆弱匹配。
     */
    private AgentErrorCategoryEnum classify(Dict payload) {
        Integer status = intValue(payload, "status", "statusCode", "httpStatus", "code");
        if (status == null) {
            return UNKNOWN;
        }
        if (status == 429) {
            return TRANSIENT;
        }
        if (status == 401) {
            return CONFIG;
        }
        if (status == 403) {
            return PERMISSION;
        }
        return status >= 500 ? TRANSIENT : UNKNOWN;
    }

    private Long retryAfterMs(Dict payload, AgentErrorCategoryEnum category) {
        if (category != TRANSIENT || payload == null) {
            return null;
        }
        Integer millis = intValue(payload, "retryAfterMs", "retry_after_ms");
        if (millis != null) {
            return Math.max(0L, millis.longValue());
        }
        Integer seconds = intValue(payload, "retryAfter", "retry_after");
        return seconds == null ? null : Math.max(0L, seconds.longValue() * 1000);
    }

    private Dict parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.parseMap(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private Integer intValue(Dict payload, String... keys) {
        if (payload == null) {
            return null;
        }
        for (String key : keys) {
            Object value = payload.get(key);
            if (value == null) {
                continue;
            }
            try {
                return Integer.valueOf(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // 非数字字段（如字符串 code）第一步不参与归类
            }
        }
        return null;
    }

    private String referenceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
