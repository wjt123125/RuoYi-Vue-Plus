package org.dromara.databus.alert;

import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.DatabusAlertRule;
import org.dromara.databus.enums.AlertNotifyStatusEnum;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警通知路由器：按规则勾选的渠道类型逐个分发，汇总逐渠道成败。
 * <p>
 * 规则的 notify_type 是逗号分隔多选，notify_config 的键与渠道类型一一对应；
 * 单渠道失败（参数缺失/调用异常/渠道未实现）只记明细不连累其他渠道。
 * 汇总口径：全成功 → SUCCESS；有成败混合 → PARTIAL；全失败 → FAILED；
 * 未勾选渠道 → SKIPPED。CHAIN 的 DISPATCHED（已投递异步执行）按成功参与汇总。
 *
 * @author databus
 */
@Slf4j
@Component
public class AlertNotifyRouter {

    /**
     * 渠道类型编码 → 通知器（Spring 收集全部 AlertNotifier 实现）。
     */
    private final Map<String, AlertNotifier> notifierMap;

    public AlertNotifyRouter(List<AlertNotifier> notifiers) {
        this.notifierMap = notifiers.stream()
            .collect(Collectors.toMap(notifier -> notifier.type().getCode(), Function.identity()));
    }

    /**
     * 把一次告警事件分发到规则勾选的全部渠道。
     *
     * @param rule  告警规则（提供 notifyType / notifyConfig）
     * @param event 告警事件载荷
     * @return 通知结果（总状态 + 逐渠道明细 JSON）
     */
    public NotifyResult notify(DatabusAlertRule rule, AlertEvent event) {
        List<String> types = splitTypes(rule.getNotifyType());
        if (types.isEmpty()) {
            return new NotifyResult(AlertNotifyStatusEnum.SKIPPED.getCode(), null);
        }
        Dict configRoot = StringUtils.isBlank(rule.getNotifyConfig()) ? null : JsonUtils.parseMap(rule.getNotifyConfig());

        Map<String, String> detail = new LinkedHashMap<>();
        for (String type : types) {
            AlertNotifier notifier = notifierMap.get(type);
            if (notifier == null) {
                detail.put(type, AlertNotifyStatusEnum.FAILED.getCode() + ": 不支持的渠道类型");
                continue;
            }
            Object channelNode = configRoot == null ? null : configRoot.get(type);
            String channelConfig = channelNode == null ? null : JsonUtils.toJsonString(channelNode);
            try {
                detail.put(type, notifier.send(event, channelConfig));
            } catch (Exception e) {
                log.warn("告警通知渠道发送失败 ruleId={} ruleName={} 渠道={} 错误={}",
                    rule.getId(), rule.getRuleName(), type, e.getMessage());
                detail.put(type, AlertNotifyStatusEnum.FAILED.getCode() + ": " + e.getMessage());
            }
        }
        return new NotifyResult(aggregate(detail.values()), JsonUtils.toJsonString(detail));
    }

    /**
     * 汇总逐渠道结果：DISPATCHED（已投递）按成功计。
     */
    private String aggregate(java.util.Collection<String> statuses) {
        boolean anySuccess = false;
        boolean anyFailed = false;
        for (String status : statuses) {
            if (status.startsWith(AlertNotifyStatusEnum.FAILED.getCode())) {
                anyFailed = true;
            } else {
                anySuccess = true;
            }
        }
        if (anyFailed && anySuccess) {
            return AlertNotifyStatusEnum.PARTIAL.getCode();
        }
        if (anyFailed) {
            return AlertNotifyStatusEnum.FAILED.getCode();
        }
        return AlertNotifyStatusEnum.SUCCESS.getCode();
    }

    private List<String> splitTypes(String notifyType) {
        if (StringUtils.isBlank(notifyType)) {
            return List.of();
        }
        return Arrays.stream(notifyType.split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .toList();
    }

    /**
     * 多渠道通知结果。
     *
     * @param status 总状态（SUCCESS/PARTIAL/FAILED/SKIPPED）
     * @param detail 逐渠道明细 JSON（如 {"EMAIL":"SUCCESS","WEBHOOK":"FAILED: ..."}）
     */
    public record NotifyResult(String status, String detail) {
    }

}
