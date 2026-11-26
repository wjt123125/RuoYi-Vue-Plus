package org.dromara.databus.agent.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具阶段展示元数据（等价 MCP {@code tools/list} 里的 {@code title}）。
 * <p>
 * 声明在 {@code @Tool} 方法上，由 {@link AgentToolStepRegistry} 在启动时自动收集：
 * 新增工具只在这里写一次标题与摘要字段，不维护任何中心映射表。
 * 漏写时启动直接失败，保证阶段标题不可能缺失。
 *
 * @author databus
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentToolStep {

    /**
     * 用户可见的动作标题（如「检索链路」），服务端下发，前端只渲染。
     */
    String title();

    /**
     * 从工具入参里按顺序取第一个非空字段作为标题摘要（如 keyword / chainName）。
     * 留空表示该阶段只显示动作，不带摘要。
     */
    String[] target() default {};

    /**
     * target 全部取空时的兜底摘要（如检索类工具留空按「最近更新」列出）。
     * 留空表示没有兜底，阶段只显示动作。
     */
    String fallback() default "";
}
