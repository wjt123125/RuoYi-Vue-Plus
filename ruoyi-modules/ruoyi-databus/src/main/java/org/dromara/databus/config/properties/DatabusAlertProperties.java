package org.dromara.databus.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 监控告警引擎配置（databus.monitor.alert.*）。
 * <p>
 * 定时扫描启用规则，判定窗口内执行表现，命中即触发告警记录并发邮件通知（恢复时翻转记录）。
 * 每轮评估独立于聚合任务，开关互不影响。
 *
 * @author databus
 */
@Data
@Component
@ConfigurationProperties(prefix = "databus.monitor.alert")
public class DatabusAlertProperties {

    /**
     * 告警评估任务开关。false 时不注册调度配置与任务 Bean（手动评估端点不受影响，仍可用）。
     */
    private boolean enabled = true;

    /**
     * Spring cron 表达式，默认每分钟整点扫描一次启用规则。
     */
    private String cron = "0 * * * * ?";

}