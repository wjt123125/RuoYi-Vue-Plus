package org.dromara.databus.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 监控指标聚合配置（databus.monitor.aggregate.*）。
 * <p>
 * 每小时把上一完整小时内的执行记录预聚合进三张宽表（databus_metric_hourly /
 * databus_metric_node_hourly / databus_metric_error_hourly），大盘只读宽表不回扫原始记录。
 * 聚合对目标小时先删后插，重复执行结果一致（幂等）。
 *
 * @author databus
 */
@Data
@Component
@ConfigurationProperties(prefix = "databus.monitor.aggregate")
public class DatabusMonitorProperties {

    /**
     * 聚合任务开关。false 时不注册调度配置与任务 Bean（手动端点不受影响，仍可用）。
     */
    private boolean enabled = true;

    /**
     * Spring cron 表达式，默认每小时第 5 分钟聚合上一个完整小时。
     */
    private String cron = "0 5 * * * ?";

    /**
     * 指标宽表保留天数：stat_hour 早于 now - retentionDays 的聚合成品被清理，
     * 与 databus_execution 保留期口径一致（原始记录被清后，宽表也随之过期）。
     */
    private int retentionDays = 30;

    /**
     * 单次批量落库行数。
     */
    private int batchSize = 500;

}