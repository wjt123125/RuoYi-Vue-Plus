package org.dromara.databus.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.config.properties.DatabusMonitorProperties;
import org.dromara.databus.domain.vo.MetricAggregateVo;
import org.dromara.databus.service.IDatabusMetricService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 监控指标小时聚合定时任务。
 * <p>
 * 每小时第 5 分钟聚合「上一个完整小时」（aggregate(null) 的默认口径），随后按保留期清理
 * 过期聚合成品。cron 走配置占位符（非法表达式启动即失败，fail-fast）；聚合与清理逻辑全在
 * Service，手动端点 POST /databus/monitor/aggregate 与本任务共用同一入口。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "databus.monitor.aggregate", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusMetricAggregateJob {

    private final IDatabusMetricService metricService;

    private final DatabusMonitorProperties properties;

    /**
     * 按配置 cron 聚合上一个完整小时，并顺带清理超过保留期的指标行。
     */
    @Scheduled(cron = "${databus.monitor.aggregate.cron:0 5 * * * ?}")
    public void aggregateHourly() {
        long start = System.currentTimeMillis();
        MetricAggregateVo result = metricService.aggregate(null);
        log.info("监控指标聚合完成：小时桶={}，链路 {} 行、节点 {} 行、错误 {} 行，耗时 {} ms",
            result.getStatHour(), result.getChainRows(), result.getNodeRows(), result.getErrorRows(),
            System.currentTimeMillis() - start);
        int deleted = metricService.cleanExpired(properties.getRetentionDays());
        if (deleted > 0) {
            log.info("监控指标保留期清理：保留天数={}，删除 {} 行", properties.getRetentionDays(), deleted);
        }
    }

}