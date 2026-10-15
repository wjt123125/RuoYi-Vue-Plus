package org.dromara.databus.alert;

import cn.hutool.core.lang.Dict;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.context.PathResolver;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.enums.AlertNotifyTypeEnum;
import org.dromara.databus.enums.TriggerSourceEnum;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 通知链路渠道：告警达到阈值时异步执行用户指定的链路，链路内可做任意告警动作
 * （钉钉/企微机器人、短信网关、工单系统、自动扩容……），产品的自我闭环（dogfooding）。
 * <p>
 * 渠道参数：{@code {"chainCode":"xxx","params":{"k":"v"}}}（params 可省）。
 * 执行入参 = 告警事件 JSON 打底，params 逐键求值后 putIfAbsent 合并进根部——
 * <b>事件字段优先</b>，params 只补空位，永不能覆盖 recordId/ruleName 等审计字段；
 * 省略 params 时入参即整份 {@link AlertEvent}（数据树根），与旧契约一致。
 * 参数值支持 {@code {{ $.路径 }}} 模板（对事件求值：整字段单表达式保留原始类型，
 * 嵌入式拼接字符串；路径不存在按 PathResolver 语义原样保留，不中断）。
 * 借此任何声明了 input_params 的既有链路都能直接复用为通知链（如 sendMail 自测链），
 * 收件人这类「规则的属性」由规则侧 params 携带，不必写进链定义。
 * <p>
 * 异步红线：评估循环每分钟跑一轮，同步等链路执行完会把慢链路（甚至挂死的外呼）卡进
 * 评估线程，因此这里只负责「投递」，结果记 DISPATCHED；链路自身成败落在
 * databus_execution 总账（trigger_source=ALERT），失败会出现在执行记录里可追查，
 * 且不参与告警评估——否则「通知链失败→再告警→再通知」就是死循环。
 *
 * @author databus
 */
@Slf4j
@Component
public class ChainAlertNotifier implements AlertNotifier {

    /**
     * 本渠道专属结果编码：已投递（异步执行，成败看 databus_execution）。
     */
    public static final String STATUS_DISPATCHED = "DISPATCHED";

    /**
     * 通知链执行线程池：核心 1、上限 2、队列 50，告警频次天然低，防积压即可。
     * 队列满走 CallerRuns 兜底（宁可在评估线程里跑一次也不丢通知）。
     */
    private final ThreadPoolExecutor notifyExecutor = new ThreadPoolExecutor(
        1, 2, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(50),
        r -> {
            Thread thread = new Thread(r, "alert-chain-notify");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.CallerRunsPolicy());

    private final DatabusExecutor databusExecutor;

    public ChainAlertNotifier(DatabusExecutor databusExecutor) {
        this.databusExecutor = databusExecutor;
    }

    @Override
    public AlertNotifyTypeEnum type() {
        return AlertNotifyTypeEnum.CHAIN;
    }

    @Override
    public String send(AlertEvent event, String configJson) throws Exception {
        Dict config = StringUtils.isBlank(configJson) ? null : JsonUtils.parseMap(configJson);
        if (config == null) {
            throw new IllegalArgumentException("通知链路渠道缺少配置");
        }
        String chainCode = config.getStr("chainCode");
        if (StringUtils.isBlank(chainCode)) {
            throw new IllegalArgumentException("通知链路渠道缺少 chainCode 参数");
        }
        Object input = buildChainInput(event, config.getObj("params"));
        notifyExecutor.submit(() -> executeChainQuietly(chainCode, input, event));
        return STATUS_DISPATCHED;
    }

    /**
     * 构建通知链执行入参：事件打底 + params 补空位；无 params 直接返回事件本身（零开销旧路径）。
     */
    private Object buildChainInput(AlertEvent event, Object paramsRaw) {
        if (paramsRaw == null) {
            return event;
        }
        if (!(paramsRaw instanceof Map)) {
            throw new IllegalArgumentException("通知链路渠道 params 须为 JSON 对象");
        }
        Map<?, ?> params = (Map<?, ?>) paramsRaw;
        if (params.isEmpty()) {
            return event;
        }
        DocumentContext eventDoc = JsonPath.parse(JsonUtils.toJsonString(event));
        Map<String, Object> root = new LinkedHashMap<>(eventDoc.read("$"));
        params.forEach((key, value) -> root.putIfAbsent(String.valueOf(key), resolveParamValue(value, eventDoc)));
        return root;
    }

    /**
     * 参数值对事件求值：非字符串 / 不含 {{ 原样；整字段单表达式求值并保留原始类型
     * （路径不存在保留原文，与 resolveEmbedded 语义对齐）；其余按嵌入模板拼字符串。
     */
    private Object resolveParamValue(Object value, DocumentContext eventDoc) {
        if (!(value instanceof String str) || !PathResolver.containsExpression(str)) {
            return value;
        }
        String whole = PathResolver.wholeExpression(str);
        if (whole != null) {
            Object resolved = PathResolver.readQuietly(whole, eventDoc);
            return resolved == null && !PathResolver.pathExists(eventDoc, whole) ? str : resolved;
        }
        return PathResolver.resolveEmbedded(str, eventDoc);
    }

    /**
     * 异步执行通知链：任何异常只记日志，绝不外抛拖垮通知线程池。
     */
    private void executeChainQuietly(String chainCode, Object input, AlertEvent event) {
        try {
            var result = databusExecutor.execute(chainCode, input, TriggerSourceEnum.ALERT);
            if (result.isSuccess()) {
                log.info("[alert] 通知链路执行成功 chainCode={} recordId={}", chainCode, event.getRecordId());
            } else {
                log.error("[alert] 通知链路执行失败 chainCode={} recordId={} 错误={}",
                    chainCode, event.getRecordId(), result.getMessage());
            }
        } catch (Exception e) {
            log.error("[alert] 通知链路调用异常 chainCode={} recordId={}", chainCode, event.getRecordId(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        notifyExecutor.shutdown();
    }

}
