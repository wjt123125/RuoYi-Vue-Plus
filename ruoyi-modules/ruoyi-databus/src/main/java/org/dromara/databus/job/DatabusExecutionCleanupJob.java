package org.dromara.databus.job;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.config.properties.DatabusExecutionCleanupProperties;
import org.dromara.databus.domain.vo.ExecutionCleanupVo;
import org.dromara.databus.service.IDatabusExecutionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 执行记录保留期定时清理任务。
 * <p>
 * cron 走配置占位符（非法表达式启动即失败，fail-fast）；清理逻辑全部在 Service，
 * 手动触发端点 POST /databus/execution/cleanup 与本任务共用同一入口。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "databus.execution.cleanup", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusExecutionCleanupJob {

    private final IDatabusExecutionService executionService;

    private final DatabusExecutionCleanupProperties properties;

    /**
     * 按配置 cron 执行清理；retentionDays 传 null 走 yml 配置值。
     */
    @Scheduled(cron = "${databus.execution.cleanup.cron:0 0 3 * * ?}")
    public void cleanup() {
        long start = System.currentTimeMillis();
        ExecutionCleanupVo result = executionService.cleanup(null);
        log.info("执行记录保留期清理完成：保留天数={}，删除总账 {} 条、节点明细 {} 行，耗时 {} ms{}",
            result.getRetentionDays(), result.getExecutionDeleted(), result.getNodeDeleted(),
            System.currentTimeMillis() - start,
            Boolean.TRUE.equals(result.getTruncated()) ? "（达到单轮上限，剩余下轮继续）" : "");
    }

}
