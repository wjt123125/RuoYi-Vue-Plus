package org.dromara.databus.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 执行记录保留期清理配置（databus.execution.cleanup.*）。
 * <p>
 * 两表只增不改无逻辑删除，过期数据由定时任务物理删除；清理规则：
 * 总账 start_time 早于 now - retentionDays 的整批删除（先节点明细后总账），
 * 不分 log_level / 不豁免状态（总账只在 afterFlow 写终态，RUNNING 行实际不存在）。
 *
 * @author databus
 */
@Data
@Component
@ConfigurationProperties(prefix = "databus.execution.cleanup")
public class DatabusExecutionCleanupProperties {

    /**
     * 清理任务开关。false 时不注册调度配置与任务 Bean（手动端点不受影响，仍可用）。
     */
    private boolean enabled = true;

    /**
     * Spring cron 表达式，默认每天凌晨 03:00。
     */
    private String cron = "0 0 3 * * ?";

    /**
     * 保留天数：start_time 严格早于 now - retentionDays 的记录被清理（恰好 N 天整留下一轮）。
     */
    private int retentionDays = 30;

    /**
     * 单批删除的总账条数。FULL 档带 longtext 快照，分批避免一条大 DELETE 长事务锁表。
     */
    private int batchSize = 500;

    /**
     * 单次任务最多批数（默认 100 批＝5 万条总账），仍有剩余则截断留到下一轮，
     * 避免首次清理海量数据长时间占库。
     */
    private int maxRounds = 100;

}
