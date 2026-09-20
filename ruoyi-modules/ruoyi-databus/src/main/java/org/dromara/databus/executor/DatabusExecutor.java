package org.dromara.databus.executor;

import com.yomahub.liteflow.builder.LiteFlowNodeBuilder;
import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.yomahub.liteflow.flow.entity.CmpStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.dromara.databus.component.script.ScriptCfg;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.context.DatabusContext;
import org.dromara.databus.context.JsonCodec;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.service.ISysDatabusConnectionService;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
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

        // 已发布链路路径此刻不持有画布树（标题随 cmpProperty 落库，monitor 阶段再解析补全）
        DatabusExecutionResult result = buildResult(executionId, chainId, startTime, endTime, costTime,
            response, context, Collections.emptyMap());
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
     * @param jsonEl      画布组件树（仅用于提取各节点用户填写的 title 透传到步骤结果；不参与执行）
     * @return 执行结果
     */
    public DatabusExecutionResult executeByEl(String elStr, Object requestData, CmpProperty jsonEl) {
        String executionId = generateExecutionId();
        Date startTime = new Date();
        log.info("[databus] 开始试运行(EL 直执) executionId={}", executionId);

        // 注意：脚本节点（script/booleanScript）的预注册由调用方 DatabusEditorController.previewRun
        // 在 generateEL 之前调 registerScriptNodes 完成——确保 verifyELExpression 校验时 FlowBus
        // 已含脚本 nodeId（validate 会查 FlowBus.getNodeMap，未注册的 nodeId 会校验失败）。

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

        // 从画布树收集 tag → title（tag 实例唯一，nodeId 可重复），随步骤结果透传给前端
        Map<String, String> titleByTag = collectTitleByTag(jsonEl);
        DatabusExecutionResult result = buildResult(executionId, PREVIEW_CHAIN_ID, startTime, endTime, costTime,
            response, context, titleByTag);
        logExecution(result);
        return result;
    }

    /**
     * 递归遍历画布组件树，把脚本节点（script 普通脚本 / booleanScript 条件脚本）注册到 FlowBus。
     * <p>
     * 脚本节点不是 Spring bean（无 {@code @LiteflowComponent}），不能像 Java 组件那样靠启动扫描注册；
     * 必须在 EL 编译/执行前用 {@link LiteFlowNodeBuilder} 动态注册。
     * <p>
     * nodeId 取画布数据空间名（{@code properties.tag}，画布强制唯一），而非组件注册名
     * （同一脚本物料可在画布出现多次，注册名 {@code script} 不可作为 nodeId 唯一标识）。
     * 前端 {@code useElTreeModel.serializeNode} 已对脚本特例：脚本叶子 {@code CmpProperty.id}
     * = 数据空间名（其他业务叶子 = 组件注册名）。
     * <p>
     * 仅服务试运行（{@code executeByEl} 路径）；已发布链路（{@code execute(chainId)} 路径）
     * 在链路设计/启用时已注册脚本节点到 FlowBus，本方法不再调用。
     * <p>异常处理：单个脚本注册失败不阻断，log.error 但继续；最外层 try-catch 包裹整个方法，
     * 失败时仅 log.error 不抛——让后续 EL 编译/执行去暴露真实问题（脚本 nodeId 缺失会
     * 在 setEL 时抛 NodeBuildException，调用方 catch 后返回失败结果）。
     *
     * @param jsonEl 画布组件树根
     */
    public void registerScriptNodes(CmpProperty jsonEl) {
        if (jsonEl == null) {
            return;
        }
        try {
            registerScriptNodesRecursive(jsonEl);
        } catch (Exception e) {
            log.error("[databus] 注册脚本节点遍历异常: {}", e.getMessage(), e);
        }
    }

    private void registerScriptNodesRecursive(CmpProperty node) {
        if (node == null) {
            return;
        }
        // 检测当前节点是否脚本叶子：id 非空 + type 为 NodeComponent/NodeBooleanComponent
        String type = node.getType();
        String id = node.getId();
        if (StringUtils.isNotBlank(id) && (NodeTypeEnum.COMMON.getMappingClazz().getSimpleName().equals(type)
            || NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName().equals(type))) {
            registerOneScriptNode(node, id, type);
        }
        // 递归 condition 位与 children
        if (node.getCondition() != null) {
            registerScriptNodesRecursive(node.getCondition());
        }
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                registerScriptNodesRecursive(child);
            }
        }
    }

    private void registerOneScriptNode(CmpProperty node, String nodeId, String type) {
        Properties props = node.getProperties();
        if (props == null || StringUtils.isBlank(props.getData())) {
            // 非脚本节点（普通 Java 组件 data 可能为空）
            return;
        }
        ScriptCfg cfg = JsonCodec.parseObject(props.getData(), ScriptCfg.class);
        if (cfg == null || StringUtils.isBlank(cfg.getScript())) {
            // data 解析失败或 script 为空，跳过——非脚本节点
            return;
        }
        String language = StringUtils.isBlank(cfg.getLanguage()) ? "groovy" : cfg.getLanguage();
        String script = cfg.getScript();
        try {
            boolean isBoolean = NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName().equals(type);
            if (isBoolean) {
                LiteFlowNodeBuilder.createScriptBooleanNode()
                    .setId(nodeId)
                    .setName("条件脚本")
                    .setLanguage(language)
                    .setScript(script)
                    .build();
            } else {
                LiteFlowNodeBuilder.createScriptNode()
                    .setId(nodeId)
                    .setName("脚本")
                    .setLanguage(language)
                    .setScript(script)
                    .build();
            }
            log.info("[databus] 注册脚本节点 nodeId={} language={} boolean={}", nodeId, language, isBoolean);
        } catch (Exception e) {
            log.error("[databus] 注册脚本节点失败 nodeId={}: {}", nodeId, e.getMessage(), e);
        }
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
     * 递归遍历画布组件树，收集 {@code tag -> title} 映射。
     * <p>
     * tag 在链路内实例唯一（同一 nodeId 可凭不同 tag 多次出现），故以 tag 为键；
     * 只收用户显式填写了非空 title 的节点，未填写的不入表（{@code NodeStep.title}
     * 保持 null，由前端按组件类型与 cfg 推断默认标题）。遍历覆盖节点自身、条件位与
     * 全部分支子节点。
     */
    private Map<String, String> collectTitleByTag(CmpProperty node) {
        Map<String, String> titleByTag = new HashMap<>();
        collectTitleByTag(node, titleByTag);
        return titleByTag;
    }

    private void collectTitleByTag(CmpProperty node, Map<String, String> titleByTag) {
        if (node == null) {
            return;
        }
        Properties props = node.getProperties();
        if (props != null && props.getTag() != null && props.getTitle() != null && !props.getTitle().isBlank()) {
            titleByTag.put(props.getTag(), props.getTitle());
        }
        collectTitleByTag(node.getCondition(), titleByTag);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectTitleByTag(child, titleByTag);
            }
        }
    }

    /**
     * 从 LiteFlow 响应组装数据总线执行结果。
     */
    private DatabusExecutionResult buildResult(String executionId, String chainId, Date startTime, Date endTime,
                                               long costTime, LiteflowResponse response, DatabusContext context,
                                               Map<String, String> titleByTag) {
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
                result.getSteps().add(toNodeStep(step, titleByTag));
            }
        }
        return result;
    }

    private DatabusExecutionResult.NodeStep toNodeStep(CmpStep step, Map<String, String> titleByTag) {
        DatabusExecutionResult.NodeStep nodeStep = new DatabusExecutionResult.NodeStep();
        nodeStep.setNodeId(step.getNodeId());
        nodeStep.setNodeName(step.getNodeName());
        nodeStep.setTag(step.getTag());
        // 透传用户在画布上填写的节点标题；未填写则保持 null，由前端推断默认标题
        if (step.getTag() != null) {
            nodeStep.setTitle(titleByTag.get(step.getTag()));
        }
        nodeStep.setSuccess(step.isSuccess());
        nodeStep.setTimeSpent(step.getTimeSpent());
        nodeStep.setStartTime(step.getStartTime());
        nodeStep.setEndTime(step.getEndTime());
        if (step.getException() != null) {
            nodeStep.setErrorMessage(step.getException().getMessage());
        }
        // NodeStepResultCollector 挂在 CmpStep 上的本步观测载荷
        if (step.getStepData() instanceof StepResultPayload payload) {
            nodeStep.setSummary(payload.getSummary());
            nodeStep.setDetailJson(payload.getDetailJson());
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
