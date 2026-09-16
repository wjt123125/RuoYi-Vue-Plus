package org.dromara.databus.interceptor;

import com.yomahub.liteflow.aop.ICmpAroundAspect;
import com.yomahub.liteflow.core.NodeComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数据总线节点执行拦截器。
 * <p>
 * 实现 LiteFlow {@link ICmpAroundAspect} 并注册为 Spring Bean 后，
 * LiteFlow 扫描器会自动将其作为全局组件切面，在每个节点的
 * beforeProcess / afterProcess / onSuccess / onError 生命周期回调。
 * <p>
 * 第一版仅做日志埋点（节点开始/结束/成功/失败 + 耗时由 LiteFlow 内部 CmpStep 记录），
 * 执行记录持久化留待 monitor 阶段基于 CmpStep 数据落库。
 *
 * @author databus
 */
@Slf4j
@Component
public class DatabusNodeInterceptor implements ICmpAroundAspect {

    @Override
    public void beforeProcess(NodeComponent cmp) {
        log.debug("[databus] 节点开始执行 nodeId={}, chainId={}, tag={}",
            cmp.getNodeId(), cmp.getChainId(), cmp.getTag());
    }

    @Override
    public void afterProcess(NodeComponent cmp) {
        log.debug("[databus] 节点执行结束 nodeId={}, chainId={}", cmp.getNodeId(), cmp.getChainId());
    }

    @Override
    public void onSuccess(NodeComponent cmp) {
        log.debug("[databus] 节点执行成功 nodeId={}", cmp.getNodeId());
    }

    @Override
    public void onError(NodeComponent cmp, Exception e) {
        log.error("[databus] 节点执行失败 nodeId={}, chainId={}, 错误={}",
            cmp.getNodeId(), cmp.getChainId(), e.getMessage(), e);
    }
}
