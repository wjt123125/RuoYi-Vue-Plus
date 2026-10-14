package org.dromara.databus.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.service.IDatabusAlertService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 告警规则评估定时任务。
 * <p>
 * 每分钟扫描启用规则，按类型判定窗口内执行表现：命中即触发一条 FIRING 记录并发邮件，
 * 恢复时翻转记录为 RECOVERED。cron 走配置占位符（非法表达式启动即失败，fail-fast）；
 * 评估逻辑全在 Service，手动端点 POST /databus/alert/evaluate 与本任务共用同一入口。
 * 仅在确实触发/恢复时打印摘要，避免每分钟刷屏。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "databus.monitor.alert", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusAlertEvaluateJob {

    private final IDatabusAlertService alertService;

    /**
     * 按配置 cron 评估全部启用规则。
     */
    @Scheduled(cron = "${databus.monitor.alert.cron:0 * * * * ?}")
    public void evaluateAlert() {
        AlertEvaluateVo result = alertService.evaluate();
        if (result.getFiredCount() > 0 || result.getRecoveredCount() > 0) {
            log.info("告警评估完成：规则 {} 条，触发 {}、恢复 {}、通知成功 {}、通知失败 {}，耗时 {} ms",
                result.getRuleCount(), result.getFiredCount(), result.getRecoveredCount(),
                result.getNotifySuccess(), result.getNotifyFailed(), result.getCostMillis());
        }
    }

}