package org.dromara.databus.executor;

import com.yomahub.liteflow.lifecycle.PostProcessFlowExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.DatabusExecutionNode;
import org.dromara.databus.enums.ExecutionStatusEnum;
import org.dromara.databus.executor.trace.ExecutionTrace;
import org.dromara.databus.executor.trace.ExecutionTraceRecorder;
import org.dromara.databus.executor.trace.NodeTraceRow;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 流程级执行记录落库钩子（LiteFlow 2.16.1+ 全局生命周期，设计档 §4.3）。
 * <p>
 * 每次 {@code execute2Resp} 在 doExecute 的 finally 中必到（成败都触发）：
 * 从 Slot 取出 DatabusContext，<b>有追踪牌且档位非 OFF</b> 时组装执行级总账——
 * 状态/错误取 {@code slot.getException()}，入参取挂牌时快照，出参取此刻数据树，
 * 耗时按挂牌起点端到端计算。
 * <p>
 * 落库顺序与容错红线：
 * <ol>
 *     <li>总账走 {@link ExecutionTraceRecorder#saveMaster} 独立事务，审计必达；</li>
 *     <li>总账成功后，FULL 档再排空节点行批量写，独立事务，失败只 warn 不连累总账；
 *     总账失败则放弃明细（避免无主孤儿行）；</li>
 *     <li>钩子全部异常自行 catch，只记日志——afterFlow 位于 finally，向外抛会覆盖
 *     业务原始异常，审计绝不影响业务执行与响应。</li>
 * </ol>
 * 试运行链路（preview-el-*）上下文不挂牌，本钩子直接跳过；ruoyi-workflow 等
 * 非数据总线链路没有 DatabusContext，同样跳过。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowExecutionTraceLifeCycle implements PostProcessFlowExecuteLifeCycle {

    private final ExecutionTraceRecorder recorder;

    @Override
    public void postProcessBeforeFlowExecute(String chainId, Slot slot) {
        // 追踪牌已在 DatabusExecutor 入口挂好，before 无需处理
    }

    @Override
    public void postProcessAfterFlowExecute(String chainId, Slot slot) {
        try {
            DatabusContext context = DatabusSlotSupport.findContext(slot);
            if (context == null) {
                return;
            }
            ExecutionTrace trace = context.getExecutionTrace();
            // 无牌（OFF/试运行/非数据总线链路）一律不落库
            if (trace == null) {
                return;
            }

            Date endTime = new Date();
            DatabusExecution master = buildMaster(trace, context, slot, endTime);

            try {
                recorder.saveMaster(master);
            } catch (Exception masterEx) {
                // 总账必达的承诺仅限"独立事务不被连坐"；存储本身故障时无法兜底，
                // 记录错误且不再写明细，避免无 execution_id 归属的孤儿节点行
                log.error("[databus] 执行记录总账落库失败 recordId={}, chainCode={}",
                    trace.getRecordId(), trace.getChainCode(), masterEx);
                return;
            }

            if (trace.isFull()) {
                saveNodesBestEffort(trace);
            }
        } catch (Exception ex) {
            // 红线：审计任何异常只记日志，绝不影响业务执行与响应
            log.error("[databus] 执行记录落库组装失败 chainId={}", chainId, ex);
        }
    }

    /**
     * 组装执行级总账（状态/错误以 Slot 异常为准）。
     */
    private DatabusExecution buildMaster(ExecutionTrace trace, DatabusContext context,
                                         Slot slot, Date endTime) {
        DatabusExecution master = new DatabusExecution();
        master.setId(trace.getRecordId());
        master.setChainId(trace.getChainId());
        master.setChainCode(trace.getChainCode());
        master.setRequestData(trace.getRequestData());
        // 失败执行也拍：保留半成品数据树，审计才能看到"在哪一步的数据状态上炸的"
        master.setResponseData(context.toJsonString());

        Exception ex = slot.getException();
        master.setStatus(ex == null
            ? ExecutionStatusEnum.SUCCESS.getCode()
            : ExecutionStatusEnum.FAILED.getCode());
        master.setErrorMsg(resolveErrorMessage(ex));
        master.setStartTime(toLocalDateTime(trace.getStartTime()));
        master.setEndTime(toLocalDateTime(endTime));
        master.setDuration(endTime.getTime() - trace.getStartTime().getTime());
        return master;
    }

    /**
     * FULL 档节点明细尽力批量落库：失败只 warn，总账已独立提交不受影响。
     */
    private void saveNodesBestEffort(ExecutionTrace trace) {
        List<NodeTraceRow> rows = trace.drainNodes();
        if (rows.isEmpty()) {
            return;
        }
        List<DatabusExecutionNode> nodes = rows.stream()
            .map(row -> toNodeEntity(trace.getRecordId(), row))
            .toList();
        try {
            recorder.saveNodes(nodes);
        } catch (Exception nodeEx) {
            log.warn("[databus] 执行记录节点明细落库失败（不影响总账）recordId={}, 节点数={}, 错误={}",
                trace.getRecordId(), nodes.size(), nodeEx.getMessage());
        }
    }

    private DatabusExecutionNode toNodeEntity(Long executionId, NodeTraceRow row) {
        DatabusExecutionNode node = new DatabusExecutionNode();
        node.setExecutionId(executionId);
        node.setNodeInstanceId(row.getNodeInstanceId());
        node.setTag(row.getTag());
        node.setNodeType(row.getNodeType());
        node.setInputJson(row.getInputJson());
        node.setOutputJson(row.getOutputJson());
        node.setStatus(row.getStatus());
        node.setErrorMsg(row.getErrorMsg());
        node.setStartTime(toLocalDateTime(row.getStartTime()));
        node.setEndTime(toLocalDateTime(row.getEndTime()));
        node.setDuration(row.getDuration());
        node.setBranchInfo(row.getBranchInfo());
        return node;
    }

    /**
     * 异常文案：优先 message，缺失（如 NPE）回退异常类名+message，全空为 null。
     */
    private String resolveErrorMessage(Exception ex) {
        if (ex == null) {
            return null;
        }
        return StringUtils.defaultIfBlank(ex.getMessage(), ex.toString());
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

}
