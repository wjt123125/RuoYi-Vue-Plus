package org.dromara.databus.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据总线告警评估的 Spring 原生调度开关。
 * <p>
 * 与聚合/清理调度配置相互独立，各绑自己的开关（本类绑 databus.monitor.alert.enabled）：
 * 关闭聚合不应连带停掉告警评估，反之亦然。@EnableScheduling 可重复注解，多配置类共存安全。
 * 告警评估是单节点幂等的维护任务（按记录状态机去重），@Scheduled 零外部依赖即可胜任；
 * 多节点部署/需要统一调度台时任务外层换成 SnailJob 的 @JobExecutor，Service 评估逻辑不动。
 *
 * @author databus
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "databus.monitor.alert", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusAlertSchedulingConfig {

}