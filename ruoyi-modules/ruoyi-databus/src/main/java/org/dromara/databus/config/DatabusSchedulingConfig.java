package org.dromara.databus.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 数据总线模块 Spring 原生调度开关。
 * <p>
 * 框架标准调度通道是 SnailJob（ruoyi-common-job 的 SnailJobConfig，snail-job.enabled=true
 * 才带 @EnableScheduling）；开发期未部署 snailjob-server，而保留期清理是单节点、幂等的
 * 维护小任务，@Scheduled 零外部依赖即可胜任（多节点重复触发＝DELETE 幂等，最坏互相少删一批，
 * 下轮补齐，不引分布式锁）。演进路径：多节点部署/需要统一调度台时，任务外层换成
 * SnailJob 的 @JobExecutor，Service 清理逻辑不动，本配置与定时 Job 一并删除。
 *
 * @author databus
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "databus.execution.cleanup", name = "enabled", havingValue = "true",
    matchIfMissing = true)
public class DatabusSchedulingConfig {

}
