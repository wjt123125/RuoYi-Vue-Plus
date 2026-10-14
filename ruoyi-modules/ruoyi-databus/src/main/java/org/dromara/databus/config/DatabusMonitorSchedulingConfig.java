package org.dromara.databus.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据总线监控聚合的 Spring 原生调度开关。
 * <p>
 * 与 DatabusSchedulingConfig 相互独立，各自绑自己的开关（本类绑 databus.monitor.aggregate.enabled）：
 * 关闭聚合不应连带停掉执行记录清理，反之亦然。指标聚合是单节点、幂等的维护任务
 * （对目标小时先删后插），@Scheduled 零外部依赖即可胜任；多节点部署/需要统一调度台时
 * 任务外层换成 SnailJob 的 @JobExecutor，Service 聚合逻辑不动。
 *
 * @author databus
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "databus.monitor.aggregate", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusMonitorSchedulingConfig {

}