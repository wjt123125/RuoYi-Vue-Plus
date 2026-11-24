package org.dromara.databus.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 助手配置（databus.agent.*）。
 * <p>
 * agent-id 是 SnailAI「应用管理」里建的智能体 id，OpenAPI 对话必填；
 * 未配置时对话端点直接报错，不影响 databus 其余功能（§17.1 故障隔离）。
 *
 * @author databus
 */
@Data
@Component
@ConfigurationProperties(prefix = "databus.agent")
public class DatabusAgentProperties {

    /**
     * SnailAI 智能体 id（OpenAPI chat 必填）。
     */
    private Long agentId;

    /**
     * 内部工具智能体 id（9002）：run 结束后生成会话标题与追问建议。
     * <p>
     * 它不带工具、不带记忆、只吃最近一轮问答，成本远低于复用主智能体；
     * 未配置时标题保持首问截断、追问建议不展示，主对话完全不受影响。
     */
    private Long utilityAgentId;

    /**
     * 是否生成追问建议。关闭后不影响自动标题（标题由 utility-agent-id 控制）。
     */
    private boolean followUpsEnabled = true;

    /**
     * 标题与建议生成的超时毫秒：超时即放弃，不回写、不报错。
     */
    private long utilityTimeoutMs = 5000;

    /**
     * run 的存活分钟数，超时守卫惰性置 EXPIRED。
     */
    private int runTtlMinutes = 30;

    /**
     * SSE 连接的空闲超时（毫秒），超时即收尾并把未完成的 run 置 STOPPED。
     */
    private long streamTimeoutMs = 300000;

    /**
     * 单次 run 的写链路纠错轮数上限：round 自增后超过该值的写操作被拒绝，不写库、不落快照。
     */
    private int maxRounds = 20;

    /**
     * 是否开放「完全访问」审批档：false 时前端下拉置灰，历史已存 FULL 的运行期按 MANUAL 处理。
     */
    private boolean allowFullAccess = false;

    /**
     * chain_run 单次回传的输出总量上限（字节），超出按路径顺序截断。
     */
    private int maxOutputBytes = 16384;

}