package org.dromara.databus.constant;

/**
 * AI Agent 域常量：业务事件码、对话级 SSE 事件名、卡片协议、工具名、权限码、错误码与长度阈值。
 * <p>
 * 事件码遵循 {@code databus:agent:<域>:<对象>:<动作>} 全冒号五段式，动作一律过去式（事件是已发生的事实，
 * 不是命令），字段名与事件码的动作段保持一致；命名正本见 docs/databus-agent-runtime.md §9.1。
 * <p>
 * 对话级 SSE 事件名（{@code card} / {@code card_reply}）走按 run 的流，不带 {@code databus:agent:} 前缀。
 *
 * @author databus
 */
public final class AgentConstants {

    private AgentConstants() {
    }

    /* ---------------- 业务事件码（全局推送通道） ---------------- */

    /**
     * 链路画布已更新（全量 cmpProperty 快照）
     */
    public static final String EVENT_CHAIN_CANVAS_UPDATED = "databus:agent:chain:canvas:updated";

    /**
     * 链路草稿已落库（chainId）
     */
    public static final String EVENT_CHAIN_DRAFT_SAVED = "databus:agent:chain:draft:saved";

    /**
     * 组件草稿已落库（componentId）
     */
    public static final String EVENT_COMPONENT_DRAFT_SAVED = "databus:agent:component:draft:saved";

    /**
     * run 生命周期结束（成功 / 失败 / 中止 / 过期）
     */
    public static final String EVENT_RUN_LIFECYCLE_ENDED = "databus:agent:run:lifecycle:ended";

    /* ---------------- 对话级 SSE 事件名（按 run 的流，无前缀） ---------------- */

    /**
     * 交互卡片下发
     */
    public static final String EVENT_CARD = "card";

    /**
     * 卡片回执
     */
    public static final String EVENT_CARD_REPLY = "card_reply";

    /**
     * 卡片动作：确认
     */
    public static final String ACTION_CONFIRM = "confirm";

    /* ---------------- 卡片协议 ---------------- */

    /**
     * 卡片类型：确认卡
     */
    public static final String KIND_CONFIRM = "confirm";

    /**
     * 卡片类型：选择卡
     */
    public static final String KIND_CHOICE = "choice";

    /**
     * 卡片类型：资源跳转卡（只做跳转，不回执）
     */
    public static final String KIND_REF = "ref";

    /**
     * 卡片呈现方式：会话内联
     */
    public static final String PRESENTATION_INLINE = "inline";

    /**
     * 资源类型：组件
     */
    public static final String RESOURCE_TYPE_COMPONENT = "component";

    /**
     * 资源类型：链路
     */
    public static final String RESOURCE_TYPE_CHAIN = "chain";

    /* ---------------- 审批档位 ---------------- */

    /**
     * 审批档位：只读工具
     */
    public static final String KIND_READ = "READ";

    /**
     * 审批档位：写工具
     */
    public static final String KIND_WRITE = "WRITE";

    /**
     * 审批档位：外部副作用
     */
    public static final String KIND_EXTERNAL = "EXTERNAL";

    /**
     * 审批档位：中性（不需要授权）
     */
    public static final String KIND_NEUTRAL = "NEUTRAL";

    /* ---------------- 工具名 ---------------- */

    public static final String TOOL_CHAIN_SEARCH = "databus_chain_search";

    public static final String TOOL_CHAIN_GET = "databus_chain_get";

    public static final String TOOL_COMPONENT_SEARCH = "databus_component_search";

    public static final String TOOL_COMPONENT_GET = "databus_component_get";

    public static final String TOOL_CHAIN_DRAFT_SAVE = "databus_chain_draft_save";

    public static final String TOOL_CHAIN_RUN = "databus_chain_run";

    public static final String TOOL_COMPONENT_DRAFT_SAVE = "databus_component_draft_save";

    public static final String TOOL_COMPONENT_RUN = "databus_component_run";

    /* ---------------- 权限码 ---------------- */

    /**
     * 通配权限（超管）
     */
    public static final String PERMISSION_ALL = "*:*:*";

    /**
     * 读工具权限点
     */
    public static final String PERMISSION_READ = "databus:editor:list";

    /**
     * 写工具权限点
     */
    public static final String PERMISSION_WRITE = "databus:editor:edit";

    /**
     * 新建工具权限点
     */
    public static final String PERMISSION_ADD = "databus:editor:add";

    /**
     * 试跑工具权限点
     */
    public static final String PERMISSION_RUN = "databus:editor:run";

    /* ---------------- 工具回执错误码 ---------------- */

    public static final String ERROR_VALIDATION = "VALIDATION";

    public static final String ERROR_CONFIG = "CONFIG";

    public static final String ERROR_PERMISSION = "PERMISSION";

    public static final String ERROR_ENVIRONMENT = "ENVIRONMENT";

    public static final String ERROR_EXTERNAL = "EXTERNAL";

    public static final String ERROR_TIMEOUT = "TIMEOUT";

    public static final String ERROR_RUNTIME = "RUNTIME";

    /* ---------------- 工具与记录的阈值 ---------------- */

    /**
     * 单次最多回给模型的节点错误条数
     */
    public static final int MAX_NODE_ERRORS = 10;

    /**
     * 单条错误文本截断长度
     */
    public static final int MAX_ERROR_TEXT = 300;

    /**
     * 试跑输出值回传上限（字节）
     */
    public static final int MAX_OUTPUT_VALUE_BYTES = 4096;

    /**
     * 校验值回传上限（字节）
     */
    public static final int MAX_CHECK_VALUE_BYTES = 512;

    public static final int DEFAULT_PAGE_SIZE = 10;

    public static final int MAX_PAGE_SIZE = 20;

    /**
     * 工具入参落库截断长度
     */
    public static final int MAX_ARGS_LEN = 2000;

    /**
     * 工具结果落库截断长度
     */
    public static final int MAX_RESULT_LEN = 4000;

    /**
     * 工具异常落库截断长度
     */
    public static final int MAX_ERROR_LEN = 2000;

    /**
     * 拒绝原因落库截断长度
     */
    public static final int MAX_REJECT_LEN = 200;

    /**
     * run 标题截断长度
     */
    public static final int TITLE_LIMIT = 200;

    /**
     * 上一轮工具摘要带入下轮的长度上限
     */
    public static final int PREVIOUS_SUMMARY_LIMIT = 4000;

    /**
     * 单个工具步骤摘要长度
     */
    public static final int STEP_DIGEST_LIMIT = 500;

    /**
     * 会话历史条数上限
     */
    public static final int CONVERSATION_HISTORY_LIMIT = 50;

    public static final int DEFAULT_CONVERSATION_LIMIT = 50;

    public static final int MAX_CONVERSATION_LIMIT = 100;

    /* ---------------- 其他 ---------------- */

    /**
     * 同一用户同一会话只允许一条 RUNNING（植入侧以此判定冲突）
     */
    public static final int SINGLE_RUNNING = 1;

    /**
     * SnailAI 调用成功返回码
     */
    public static final int SNAIL_AI_SUCCESS = 1;

    /**
     * 快照记录的操作类型：新建
     */
    public static final String OPERATION_CREATE = "CREATE";

    /**
     * 快照记录的操作类型：修改
     */
    public static final String OPERATION_UPDATE = "UPDATE";
}
