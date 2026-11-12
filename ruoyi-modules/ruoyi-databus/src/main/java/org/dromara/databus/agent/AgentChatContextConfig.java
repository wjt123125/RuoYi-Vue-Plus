package org.dromara.databus.agent;

import com.aizuda.snail.ai.agent.common.context.AgentChatContextThreadLocalAccessor;
import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * 补注册 SnailAI 会话上下文的 ThreadLocalAccessor（验证门 ⑥ 的前提）。
 * <p>
 * SnailAI 1.1.1 只调用了 {@code Hooks.enableAutomaticContextPropagation()}，
 * 但工程内没有 micrometer Spring Boot 自动装配包，accessor 不会自动注册；
 * 不注册则 gRPC 派回的工具线程读不到 conversationId，守卫 ② 必然拒绝。
 * <p>
 * Bean 初始化早于任何对话，因此在这里注册即可覆盖所有工具调用。
 *
 * @author databus
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "snail-ai", name = "enabled", havingValue = "true")
public class AgentChatContextConfig {

    @PostConstruct
    public void registerAgentChatContext() {
        ContextRegistry registry = ContextRegistry.getInstance();
        String key = AgentChatContextThreadLocalAccessor.KEY;
        boolean registered = registry.getThreadLocalAccessors().stream()
            .anyMatch(accessor -> key.equals(accessor.key()));
        if (registered) {
            return;
        }
        registry.registerThreadLocalAccessor(new AgentChatContextThreadLocalAccessor());
        log.info("[databus-agent] 已注册 SnailAI 会话上下文 ThreadLocalAccessor，工具线程可读取 conversationId");
    }

}