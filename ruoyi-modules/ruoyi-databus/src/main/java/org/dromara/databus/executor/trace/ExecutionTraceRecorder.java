package org.dromara.databus.executor.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.DatabusExecutionNode;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.mapper.DatabusExecutionNodeMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 执行记录落库器（设计档 §4.3：总账必达、明细尽力）。
 * <p>
 * 两类写入各自独立事务（{@link Propagation#REQUIRES_NEW}），与业务执行事务互不连坐：
 * <ul>
 *     <li>{@link #saveMaster}：执行级总账，审计必达，独立事务先提交；</li>
 *     <li>{@link #saveNodes}：FULL 档节点明细，批量插入；调用方 catch 后只 warn，
 *     明细失败绝不连累总账。</li>
 * </ul>
 * 必须经<b>另一个 Bean</b>（{@code FlowExecutionTraceLifeCycle}）调用——同类自调用
 * 不走 Spring 代理，REQUIRES_NEW 不生效。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionTraceRecorder {

    private final DatabusExecutionMapper executionMapper;

    private final DatabusExecutionNodeMapper nodeMapper;

    /**
     * 独立事务写执行级总账（审计必达）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveMaster(DatabusExecution execution) {
        executionMapper.insert(execution);
    }

    /**
     * 独立事务批量写节点明细；空列表直接返回。明细失败由调用方 warn 兜底。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void saveNodes(List<DatabusExecutionNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        nodeMapper.insertBatch(nodes);
    }

}
