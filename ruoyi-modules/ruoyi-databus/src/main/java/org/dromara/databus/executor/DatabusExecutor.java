package org.dromara.databus.executor;

import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.entity.CmpStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.service.ISysDatabusConnectionService;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * 数据总线执行器。
 * <p>
 * 作为链路执行的统一入口，封装 LiteFlow {@link FlowExecutor} 调用，负责：
 * <ul>
 *     <li>生成 executionId</li>
 *     <li>从请求数据初始化 {@link DatabusContext}</li>
 *     <li>调用 LiteFlow 执行链路</li>
 *     <li>组装 {@link DatabusExecutionResult}（含节点步骤与上下文快照）</li>
 *     <li>记录执行日志（数据库持久化留待 monitor 阶段）</li>
 * </ul>
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabusExecutor {

    private final FlowExecutor flowExecutor;

    private final ISysDatabusConnectionService connectionService;

    /**
     * 同步执行链路。
     *
     * @param chainId     链路编码
     * @param requestData 执行入参（任意对象，内部转为 DatabusContext 的初始 JSON 文档）
     * @return 执行结果
     */
    public DatabusExecutionResult execute(String chainId, Object requestData) {
        String executionId = generateExecutionId();
        Date startTime = new Date();
        log.info("[databus] 开始执行链路 chainId={}, executionId={}", chainId, executionId);

        DatabusContext context = DatabusContext.fromObject(requestData);
        injectConnections(context);

        LiteflowResponse response = flowExecutor.execute2Resp(chainId, requestData, context);

        Date endTime = new Date();
        long costTime = endTime.getTime() - startTime.getTime();

        DatabusExecutionResult result = buildResult(executionId, chainId, startTime, endTime, costTime, response, context);
        logExecution(result);
        return result;
    }

    /**
     * 按 EL 字符串直接执行（不落库、不依赖规则源），供编辑器试运行使用。
     * <p>
     * <b>不能用 {@code flowExecutor.execute2RespWithEL}</b>：它内部会先执行
     * {@code ElRegexUtil.normalize}（{@code replace("'", "\"").replaceAll("\\s","")}），
     * 该处理不区分字符串字面量内外，会把 {@code .data("...")} 值里的空格删掉
     * （SQL 会被粘成 {@code selectuserid...}）、把单引号替换成双引号破坏 JSON。
     * 此问题在 LiteFlow 2.16.0/2.16.1 及当前 master 均存在（旧 issue #I5ZS8I
     * 的修复在 2.11.2 重写后回归丢失）。
     * <p>
     * 绕行方式：用 {@link LiteFlowChainELBuilder} 以<b>原始 EL 文本</b>建链——其
     * {@code setEL} 把原文存入 chain（normalize 仅用于计算缓存 MD5），{@code build}
     * 编译时 qlexpress4 拿到的也是原文——再按 chainId 调普通的 {@code execute2Resp}，
     * 与 execute2RespWithEL 缓存未命中分支的最终执行路径完全等价。
     *
     * @param elStr       EL 表达式（调用方负责已通过语法校验）
     * @param requestData 执行入参（任意对象，内部转为 DatabusContext 的初始 JSON 文档，即文档根 {@code $}）
     * @return 执行结果
     */
    public DatabusExecutionResult executeByEl(String elStr, Object requestData) {
        String executionId = generateExecutionId();
        Date startTime = new Date();
        log.info("[databus] 开始试运行(EL 直执) executionId={}", executionId);

        DatabusContext context = DatabusContext.fromObject(requestData);
        injectConnections(context);

        // 每次试运行使用独立 chainId，避免 FlowBus 中同名 chain 被反复重编译
        String runtimeChainId = PREVIEW_CHAIN_ID + "-" + executionId;
        LiteFlowChainELBuilder.createChain()
            .setChainId(runtimeChainId)
            .setEL(elStr)
            .build();
        LiteflowResponse response = flowExecutor.execute2Resp(runtimeChainId, requestData, context);

        Date endTime = new Date();
        long costTime = endTime.getTime() - startTime.getTime();

        DatabusExecutionResult result = buildResult(executionId, PREVIEW_CHAIN_ID, startTime, endTime, costTime, response, context);
        logExecution(result);
        return result;
    }

    /**
     * 试运行结果中使用的虚拟链路标识（真实 chainId 不落库、不存在）。
     */
    private static final String PREVIEW_CHAIN_ID = "preview-el";

    /**
     * 生成执行记录业务 id。
     */
    private String generateExecutionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 从 LiteFlow 响应组装数据总线执行结果。
     */
    private DatabusExecutionResult buildResult(String executionId, String chainId, Date startTime, Date endTime,
                                               long costTime, LiteflowResponse response, DatabusContext context) {
        DatabusExecutionResult result = new DatabusExecutionResult();
        result.setExecutionId(executionId);
        result.setChainId(chainId);
        result.setStartTime(startTime);
        result.setEndTime(endTime);
        result.setCostTime(costTime);
        result.setSuccess(response.isSuccess());
        // 执行异常的文案通常在 getMessage()，缺失时回退取 cause
        String message = response.getMessage();
        if ((message == null || message.isBlank()) && !response.isSuccess() && response.getCause() != null) {
            message = response.getCause().getMessage();
        }
        result.setMessage(message);
        result.setContextJson(context.toJsonString());

        // 提取节点执行步骤（按执行顺序）
        Queue<CmpStep> stepQueue = response.getExecuteStepQueue();
        if (stepQueue != null) {
            for (CmpStep step : stepQueue) {
                result.getSteps().add(toNodeStep(step));
            }
        }
        return result;
    }

    private DatabusExecutionResult.NodeStep toNodeStep(CmpStep step) {
        DatabusExecutionResult.NodeStep nodeStep = new DatabusExecutionResult.NodeStep();
        nodeStep.setNodeId(step.getNodeId());
        nodeStep.setNodeName(step.getNodeName());
        nodeStep.setTag(step.getTag());
        nodeStep.setSuccess(step.isSuccess());
        nodeStep.setTimeSpent(step.getTimeSpent());
        nodeStep.setStartTime(step.getStartTime());
        nodeStep.setEndTime(step.getEndTime());
        if (step.getException() != null) {
            nodeStep.setErrorMessage(step.getException().getMessage());
        }
        return nodeStep;
    }

    /**
     * 记录执行日志（先日志，存库留待 monitor 阶段）。
     */
    private void logExecution(DatabusExecutionResult result) {
        if (result.isSuccess()) {
            log.info("[databus] 链路执行成功 executionId={}, chainId={}, 耗时={}ms, 节点数={}",
                result.getExecutionId(), result.getChainId(), result.getCostTime(), result.getSteps().size());
        } else {
            log.error("[databus] 链路执行失败 executionId={}, chainId={}, 耗时={}ms, 错误={}",
                result.getExecutionId(), result.getChainId(), result.getCostTime(), result.getMessage());
        }
    }

    /**
     * 兼容旧调用：仅取 LiteFlow 原生响应（不构建数据总线结果），供过渡阶段使用。
     */
    public LiteflowResponse executeRaw(String chainId, Object requestData) {
        DatabusContext context = DatabusContext.fromObject(requestData);
        injectConnections(context);
        return flowExecutor.execute2Resp(chainId, requestData, context);
    }

    /**
     * 把启用的连接（enabled=Y）从 sys_databus_connection 加载并注入到当前执行上下文。
     * <p>每次执行都重新查一次 DB，确保连接管理页修改后立即可见；后续如有性能压力可加缓存。
     * <p>单条加载失败不阻断执行（已记录 ERROR 日志），后续组件取该 connectionId 时会抛"未注册"。
     */
    private void injectConnections(DatabusContext context) {
        List<Connection> connections = connectionService.loadEnabledConnections();
        if (connections == null || connections.isEmpty()) {
            log.debug("[databus] 无启用的连接，跳过注入");
            return;
        }
        for (Connection conn : connections) {
            context.registerConnection(conn);
        }
        log.info("[databus] 注入连接 {} 个: {}", connections.size(),
            connections.stream().map(Connection::getId).toList());
    }

    /**
     * 获取 LiteFlow 原生步骤 Map（key 为 chainId），供需要细粒度步骤信息的场景使用。
     */
    public Map<String, List<CmpStep>> getExecuteSteps(LiteflowResponse response) {
        return response.getExecuteSteps();
    }
}
