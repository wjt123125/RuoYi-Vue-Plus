package org.dromara.databus.alert;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.service.IDatabusAlertService;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 告警事件驱动触发器：链路执行总账落库后立即异步评估该链路的启用规则（秒级告警）。
 * <p>
 * 为什么异步：评估含窗口查询与（命中时的）邮件/Webhook 同步调用，直接挂在执行线程上会拖慢
 * 业务响应；专用小线程池投递即返回。队列满走丢弃策略——分钟级定时评估（
 * {@code DatabusAlertEvaluateJob}）是兜底，丢一次事件最坏退回到分钟级延迟，绝不阻塞执行线程。
 * 并发安全：事件评估与定时评估撞同一规则时，由 Service 内按规则 JVM 锁串行去重。
 *
 * @author databus
 */
@Slf4j
@Component
public class AlertEvaluateTrigger {

    private final IDatabusAlertService alertService;

    private final ThreadPoolExecutor executor;

    public AlertEvaluateTrigger(IDatabusAlertService alertService) {
        this.alertService = alertService;
        this.executor = new ThreadPoolExecutor(1, 2, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            r -> {
                Thread t = new Thread(r, "alert-evaluate-trigger");
                t.setDaemon(true);
                return t;
            },
            (task, pool) -> log.warn("告警事件评估队列已满（{}），丢弃本次触发，等分钟级定时评估兜底", pool.getQueue().size()));
    }

    /**
     * 投递一次单链路评估（立即返回，绝不抛异常给执行线程）。
     *
     * @param chainCode 刚落账的执行所属链路编码
     */
    public void trigger(String chainCode) {
        try {
            executor.execute(() -> evaluateQuietly(chainCode));
        } catch (Exception e) {
            // 线程池 shutdown 后 RejectedExecutionException 等：只记日志，不影响执行路径
            log.warn("告警事件评估投递失败 chainCode={} err={}", chainCode, e.getMessage());
        }
    }

    /**
     * 评估并仅在发生触发/恢复时打印摘要（成功路径全 catch，异步线程无调用方可兜底）。
     */
    private void evaluateQuietly(String chainCode) {
        try {
            AlertEvaluateVo result = alertService.evaluateChain(chainCode);
            if (result.getFiredCount() > 0 || result.getRecoveredCount() > 0) {
                log.info("告警事件评估完成 chainCode={}：触发 {}、恢复 {}，耗时 {} ms",
                    chainCode, result.getFiredCount(), result.getRecoveredCount(), result.getCostMillis());
            }
        } catch (Exception e) {
            log.error("告警事件评估失败 chainCode={} err={}", chainCode, e.getMessage(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }

}
