package org.dromara.databus.agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具阶段元数据注册表：启动时扫描所有 {@code @Tool} 方法上的 {@link AgentToolStep}，自动建表。
 * <p>
 * 这是对标 MCP {@code tools/list} 的 {@code title}：标题归工具定义本身，不在这里逐条列工具，
 * 也不需要前端认识工具码。新增工具只在它自己的 {@code @Tool} 方法上声明一次；
 * 任何 {@code @Tool} 漏写 {@link AgentToolStep} 都会让应用启动失败，避免运行期冒出无名阶段。
 *
 * @author databus
 */
@Component
public class AgentToolStepRegistry {

    /**
     * 标题摘要的最大长度，超出截断，避免窄侧栏里标题换行
     */
    private static final int TARGET_LIMIT = 24;

    private final Map<String, Meta> metas;

    public AgentToolStepRegistry(ListableBeanFactory beanFactory) {
        Map<String, Meta> collected = new LinkedHashMap<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanFactory.getType(beanName, false);
            if (type == null) {
                continue;
            }
            ReflectionUtils.doWithMethods(type, method -> {
                Tool tool = method.getAnnotation(Tool.class);
                if (tool == null) {
                    return;
                }
                AgentToolStep step = method.getAnnotation(AgentToolStep.class);
                String name = StringUtils.hasText(tool.name()) ? tool.name() : method.getName();
                if (step == null) {
                    throw new IllegalStateException("工具 " + name + " 缺少 @AgentToolStep，阶段标题无法生成");
                }
                Meta previous = collected.putIfAbsent(name,
                    new Meta(step.title(), List.of(step.target()), step.fallback()));
                if (previous != null) {
                    throw new IllegalStateException("工具阶段元数据重复声明：" + name);
                }
            });
        }
        this.metas = Map.copyOf(collected);
    }

    /**
     * 生成阶段标题：动作 +（可选）入参摘要。
     *
     * @param tool 工具名
     * @param args 工具入参快照
     * @return 用户可见标题；未登记的工具回落为工具名
     */
    public String titleOf(String tool, Map<String, Object> args) {
        Meta meta = metas.get(tool);
        if (meta == null) {
            return tool;
        }
        String summary = summary(meta.target(), args);
        if (summary == null && !meta.fallback().isEmpty()) {
            summary = meta.fallback();
        }
        return summary == null ? meta.title() : meta.title() + "「" + summary + "」";
    }

    private static String summary(List<String> keys, Map<String, Object> args) {
        if (args == null) {
            return null;
        }
        for (String key : keys) {
            Object value = args.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) {
                continue;
            }
            return text.length() > TARGET_LIMIT ? text.substring(0, TARGET_LIMIT) + "…" : text;
        }
        return null;
    }

    /**
     * 单个工具的展示元数据。
     *
     * @param title    动作标题
     * @param target   摘要候选入参字段（按顺序取第一个非空）
     * @param fallback target 全空时的兜底摘要
     */
    private record Meta(String title, List<String> target, String fallback) {
    }
}
