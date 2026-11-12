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
     * run 的存活分钟数，超时守卫惰性置 EXPIRED。
     */
    private int runTtlMinutes = 30;

    /**
     * SSE 连接的空闲超时（毫秒），超时即收尾并把未完成的 run 置 STOPPED。
     */
    private long streamTimeoutMs = 300000;

}