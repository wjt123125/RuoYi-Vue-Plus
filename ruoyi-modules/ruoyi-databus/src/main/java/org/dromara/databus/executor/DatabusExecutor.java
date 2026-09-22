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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

/**
 * 数据总线执行器，链路执行的统一入口。
 * <p>
 * 理解本类的钥匙：系统里有<b>两条互不相干的执行路径</b>——
 * <ol>
 *     <li><b>正式执行</b>（{@link #execute}）：链路已发布，EL 存在 Rule-DB 的 lf_chain 表、
 *     脚本节点由 lf_script 表懒加载。本方法只按 chainId 调 {@code execute2Resp}，不建链、
 *     不注册节点；</li>
 *     <li><b>编辑器试运行</b>（{@link #executeByEl}）：画布草稿链，不落库、不经过 Rule-DB。
 *     每次用随机 chainId 把前端传来的 EL 原文动态建链即执即弃；脚本节点先由
 *     {@link #registerScriptNodes} 临时注册进 FlowBus。</li>
 * </ol>
 * 两条路径共用的收尾：生成 executionId、初始化 {@link DatabusContext}、注入连接配置、
 * 把 LiteFlow 响应组装成 {@link DatabusExecutionResult}（每步状态/耗时/观测载荷）并打日志。
 * 执行记录的数据库持久化留待 monitor 阶段。
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
        // 计时口径：起点放在 execute2Resp 之前，costTime 是数据总线端到端耗时
        // （含上下文初始化 + 连接查库注入），不是纯 LiteFlow 引擎耗时——
        // LiteflowResponse/Slot 不提供链路级总耗时，节点级耗时则直接取各 CmpStep
        Date startTime = new Date();
        log.info("[databus] 开始执行链路 chainId={}, executionId={}", chainId, executionId);

        DatabusContext context = DatabusContext.fromObject(requestData);
        injectConnections(context);

        try {
            LiteflowResponse response = flowExecutor.execute2Resp(chainId, requestData, context);

            Date endTime = new Date();
            long costTime = endTime.getTime() - startTime.getTime();

            // 已发布链路路径此刻不持有画布树（标题随 cmpProperty 落库，monitor 阶段再解析补全）
            DatabusExecutionResult result = buildResult(executionId, chainId, startTime, endTime, costTime,
                response, context, Collections.emptyMap());
            logExecution(result);
            return result;
        } finally {
            // 循环索引 ThreadLocal 是节点级临时状态，执行结束（含异常路径）即清，防工作线程复用串台
            context.clearLoopState();
        }
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
        // 计时口径：起点放在 execute2Resp 之前，costTime 是数据总线端到端耗时
        // （含上下文初始化 + 连接查库注入），不是纯 LiteFlow 引擎耗时——
        // LiteflowResponse/Slot 不提供链路级总耗时，节点级耗时则直接取各 CmpStep
        Date startTime = new Date();
        log.info("[databus] 开始试运行(EL 直执) executionId={}", executionId);

        // 注意：脚本节点（script/booleanScript）的预注册由调用方 DatabusEditorController.previewRun
        // 在 generateEL 之前调 registerScriptNodes 完成——确保 verifyELExpression 校验时 FlowBus
        // 已含脚本 nodeId（validate 会查 FlowBus.getNodeMap，未注册的 nodeId 会校验失败）。

        DatabusContext context = DatabusContext.fromObject(requestData);
        injectConnections(context);

        try {
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
        } finally {
            // 循环索引 ThreadLocal 是节点级临时状态，执行结束（含异常路径）即清，防工作线程复用串台
            context.clearLoopState();
        }
    }

    /**
     * 把画布树里的脚本节点（script 普通脚本 / booleanScript 条件脚本）注册到 FlowBus。
     * <p>
     * <b>仅服务试运行</b>（{@code executeByEl} 路径）：脚本节点不是 Spring bean（无
     * {@code @LiteflowComponent}），不能像 Java 组件那样靠启动扫描注册，必须在 EL 编译/执行前
     * 用 {@link LiteFlowNodeBuilder} 动态注册。已发布链路的脚本节点由 Rule-DB 的 lf_script 表
     * 懒加载（发布时 {@code RulePublishService} 推送），不走本方法。
     * <p>
     * 节点遍历与脚本解析直接复用 {@link #collectScriptNodes}——试运行注册与发布推送 lf_script
     * 是同一套叶子判定口径，避免两处递归各写一份。单个脚本注册失败只 log.error 不阻断其余节点；
     * 最外层再兜一道遍历异常，失败后让后续 EL 编译暴露真实问题（nodeId 缺失会在 setEL 时
     * 抛 NodeBuildException，调用方 catch 后返回失败结果）。
     * <p>
     * 调用时机：{@code DatabusEditorController.previewRun} 在 generateEL/EL 校验之前调用——
     * validate 会查 FlowBus.getNodeMap，未注册的 nodeId 会导致校验失败。
     *
     * @param jsonEl 画布组件树根（null 直接返回）
     */
    public void registerScriptNodes(CmpProperty jsonEl) {
        if (jsonEl == null) {
            return;
        }
        try {
            for (ScriptNodeSpec spec : collectScriptNodes(jsonEl)) {
                registerOneScriptNode(spec);
            }
        } catch (Exception e) {
            log.error("[databus] 注册脚本节点遍历异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 按发布规格把单个脚本注册进 FlowBus；任何失败只记日志，不影响其他节点继续注册。
     */
    private void registerOneScriptNode(ScriptNodeSpec spec) {
        try {
            boolean isBoolean = "boolean_script".equals(spec.type());
            if (isBoolean) {
                LiteFlowNodeBuilder.createScriptBooleanNode()
                    .setId(spec.nodeId())
                    .setName("条件脚本")
                    .setLanguage(spec.language())
                    .setScript(spec.script())
                    .build();
            } else {
                LiteFlowNodeBuilder.createScriptNode()
                    .setId(spec.nodeId())
                    .setName("脚本")
                    .setLanguage(spec.language())
                    .setScript(spec.script())
                    .build();
            }
            log.info("[databus] 注册脚本节点 nodeId={} language={} boolean={}",
                spec.nodeId(), spec.language(), isBoolean);
        } catch (Exception e) {
            log.error("[databus] 注册脚本节点失败 nodeId={}: {}", spec.nodeId(), e.getMessage(), e);
        }
    }

    /**
     * 脚本节点规格，一个脚本叶子一条：发布链路时由 {@code RulePublishService} 推送
     * Rule-DB lf_script；试运行时由 {@link #registerScriptNodes} 据此注册 FlowBus。
     *
     * @param nodeId   脚本 nodeId = 画布数据空间名（EL 里的引用名）
     * @param type     对齐 PublishScriptRequest 的脚本类型：script / boolean_script
     * @param language 脚本语言（缺省 groovy）
     * @param script   脚本源码
     */
    public record ScriptNodeSpec(String nodeId, String type, String language, String script) {
    }

    /**
     * 递归遍历画布组件树，收集全部脚本节点规格。
     * <p>
     * 这是脚本叶子的<b>唯一遍历入口</b>，两处复用：发布时 {@code RulePublishService} 拿结果
     * 推送 lf_script；试运行时 {@link #registerScriptNodes} 拿结果注册 FlowBus。
     * <p>
     * 判定口径：id 非空 + type 为 NodeComponent/NodeBooleanComponent + data 解析出非空
     * ScriptCfg.script（非脚本叶子 data 为空或无 script 字段，自然排除）。
     * <p>
     * 注意：lf_script 主键为 (application_name, node_id)，脚本 nodeId 为画布数据空间名，
     * 不区分链路——跨链路同名 tag 的不同脚本存在覆盖风险，由调用方 RulePublishService
     * 在发布时做冲突校验。
     *
     * @param jsonEl 画布组件树根（可为 null）
     * @return 脚本节点规格列表（无脚本节点时为空列表）
     */
    public List<ScriptNodeSpec> collectScriptNodes(CmpProperty jsonEl) {
        List<ScriptNodeSpec> specs = new ArrayList<>();
        collectScriptNodesRecursive(jsonEl, specs);
        return specs;
    }

    private void collectScriptNodesRecursive(CmpProperty node, List<ScriptNodeSpec> specs) {
        if (node == null) {
            return;
        }
        String type = node.getType();
        String id = node.getId();
        if (StringUtils.isNotBlank(id) && (NodeTypeEnum.COMMON.getMappingClazz().getSimpleName().equals(type)
            || NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName().equals(type))) {
            parseScriptSpec(node, id, type).ifPresent(specs::add);
        }
        if (node.getCondition() != null) {
            collectScriptNodesRecursive(node.getCondition(), specs);
        }
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectScriptNodesRecursive(child, specs);
            }
        }
    }

    /**
     * 解析单个叶子为脚本规格；非脚本叶子（data 为空 / 无 script 字段）返回 empty。
     */
    private Optional<ScriptNodeSpec> parseScriptSpec(CmpProperty node, String nodeId, String type) {
        Properties props = node.getProperties();
        if (props == null || StringUtils.isBlank(props.getData())) {
            return Optional.empty();
        }
        ScriptCfg cfg = JsonCodec.parseObject(props.getData(), ScriptCfg.class);
        if (cfg == null || StringUtils.isBlank(cfg.getScript())) {
            return Optional.empty();
        }
        String language = StringUtils.isBlank(cfg.getLanguage()) ? "groovy" : cfg.getLanguage();
        boolean isBoolean = NodeTypeEnum.BOOLEAN.getMappingClazz().getSimpleName().equals(type);
        return Optional.of(new ScriptNodeSpec(nodeId, isBoolean ? "boolean_script" : "script",
            language, cfg.getScript()));
    }

    /**
     * 试运行结果中使用的虚拟链路标识（真实 chainId 不落库、不存在）。
     */
    private static final String PREVIEW_CHAIN_ID = "preview-el";

    /**
     * 生成执行记录业务 id（UUID 去横线）。
     * <p>
     * 不复用 LiteFlow 自带的 {@code response.getRequestId()}：试运行在动态建链阶段
     * （{@code LiteFlowChainELBuilder.build()}）就可能失败，此时根本拿不到 response；
     * 自造 id 保证任何失败结果都有追踪号。
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

}
