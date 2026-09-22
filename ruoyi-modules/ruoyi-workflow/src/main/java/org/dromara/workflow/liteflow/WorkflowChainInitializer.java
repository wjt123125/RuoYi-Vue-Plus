package org.dromara.workflow.liteflow;

import com.yomahub.liteflow.builder.el.LiteFlowChainELBuilder;
import com.yomahub.liteflow.core.FlowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 工作流静态链路初始化器。
 * <p>
 * 原 4 条任务办理链由 {@code liteflow.rule-source=classpath:liteflow/*.el.xml} 加载；
 * 数据总线接入 LiteFlow Rule-DB 后 rule-source 与 rule-db 配置互斥（同配启动报错），
 * 故规则文件已删除，链路改为在启动时用 {@link LiteFlowChainELBuilder} 直接建链。
 * <p>
 * 静态链随代码部署走，不进 Rule-DB（lf_chain 只管用户可变的数据总线链路）；
 * 手写 chain 与 Rule-DB 共存的前提是 id 不与存储中的链路撞车（数据总线链路以
 * chain_code 为 id，命名空间天然隔离），见 LiteFlow v2.16.1 Rule-DB 边界说明。
 * <p>
 * 建链时机用 {@link ApplicationRunner}：框架自身的 {@code LiteflowExecutorInit}
 * 是 SmartInitializingSingleton，在其回调里调 {@code flowExecutor.init(true)}
 * 完成节点注册与 Rule-DB 初始化；各 SmartInitializingSingleton 的回调顺序不保证
 * （本类最初也用 SmartInitializingSingleton 时排在框架之前，节点尚未注册导致
 * ELParseException 启动失败）。ApplicationRunner 在容器刷新完成后执行，此时
 * {@code @LiteflowComponent} 组件必然已注册到 FlowBus。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(value = "liteflow.enable", havingValue = "true")
public class WorkflowChainInitializer implements ApplicationRunner {

    private final FlowExecutor flowExecutor;

    /**
     * 启动流程链路：
     * 1. 初始化请求并查询已有实例
     * 2. 已存在实例时走续提交并返回当前任务
     * 3. 不存在实例时加载流程定义并补齐变量
     * 4. 新启动实例、保存业务扩展并构建返回结果
     */
    private static final String START_PROCESS_CHAIN_EL = """
        THEN(
            startPrepareRequest,
            IF(
                startExists,
                startResume,
                THEN(
                    startPrepareInstance,
                    startExecute
                )
            )
        )""";

    /**
     * 办理任务链路：
     * 1. 加载任务实例、补齐变量并解析办理人
     * 2. 构建参数并执行当前任务跳转
     * 3. 开启自动审批时继续办理当前处理人的后续任务
     */
    private static final String COMPLETE_TASK_CHAIN_EL = """
        THEN(
            completePrepare,
            completeExecute,
            IF(
                completeNeedAutoPass,
                completeAutoPass,
                noop
            )
        )""";

    /**
     * 任务操作链路：
     * 1. 解析操作类型、校验入参并构建参数
     * 2. 加载任务节点并校验操作约束
     * 3. 执行任务操作
     * 4. 操作成功且配置消息类型时发送通知
     */
    private static final String TASK_OPERATION_CHAIN_EL = """
        THEN(
            taskOpPrepare,
            taskOpLoad,
            taskOpExecute,
            IF(
                taskOpNeedNotify,
                taskOpNotify,
                noop
            )
        )""";

    /**
     * 删除流程实例链路：
     * 1. 按业务 id 或实例 id 加载待删除实例
     * 2. 存在待删除实例时校验权限并发布业务删除事件
     * 3. 按运行实例或历史实例模式执行删除；不存在实例时直接返回 false
     */
    private static final String DELETE_INSTANCE_CHAIN_EL = """
        THEN(
            instanceDeleteLoad,
            IF(
                instanceDeleteExists,
                THEN(
                    instanceDeleteEvent,
                    instanceDeleteExecute
                ),
                noop
            )
        )""";

    @Override
    public void run(ApplicationArguments args) {
        buildChain("startProcessChain", START_PROCESS_CHAIN_EL);
        buildChain("completeTaskChain", COMPLETE_TASK_CHAIN_EL);
        buildChain("taskOperationChain", TASK_OPERATION_CHAIN_EL);
        buildChain("deleteInstanceChain", DELETE_INSTANCE_CHAIN_EL);
    }

    /**
     * 用原始 EL 文本建链（setEL 保留原文，与 rule-source 文件加载语义等价）。
     * 建链失败直接抛出终止启动——工作流任务办理强依赖这 4 条链，缺链即不可用，
     * 静默降级会把故障推迟到首次办理时才暴露。
     */
    private void buildChain(String chainId, String el) {
        LiteFlowChainELBuilder.createChain()
            .setChainId(chainId)
            .setEL(el)
            .build();
        log.info("[workflow] 静态链路已加载 chainId={}", chainId);
    }

}
