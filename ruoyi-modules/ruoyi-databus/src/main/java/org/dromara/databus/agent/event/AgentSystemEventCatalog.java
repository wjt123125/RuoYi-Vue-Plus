package org.dromara.databus.agent.event;

import org.dromara.common.core.exception.ServiceException;

import java.util.Map;

/**
 * 系统事件目录：事件码 → 用户可见文案 / 模型事实句（可含行为要求）。
 * <p>
 * 分工写在类型上：{@code display} 只进用户气泡（落 run.intent），
 * {@code fact} 只进模型上下文（buildChatContent），二者永不混用。
 * 新增事件只需在这里加一条映射，前端不再出现业务中文。
 *
 * @author databus
 */
public final class AgentSystemEventCatalog {

    /**
     * 组件草稿已在审阅页采纳
     */
    public static final String COMPONENT_DRAFT_ADOPTED = "component.draft.adopted";

    /**
     * 组件草稿已在审阅页被拒绝并销毁
     */
    public static final String COMPONENT_DRAFT_REJECTED = "component.draft.rejected";

    /**
     * 目录项
     *
     * @param display 用户可见的一句话（历史回放也用它）
     * @param fact    只给模型看的事实与行为要求
     */
    public record Entry(String display, String fact) {
    }

    @FunctionalInterface
    private interface EntryBuilder {
        Entry build(Map<String, Object> payload);
    }

    private static final Map<String, EntryBuilder> ENTRIES = Map.of(
        COMPONENT_DRAFT_ADOPTED, AgentSystemEventCatalog::draftAdopted,
        COMPONENT_DRAFT_REJECTED, AgentSystemEventCatalog::draftRejected
    );

    private AgentSystemEventCatalog() {
    }

    /**
     * 按事件码取目录项。
     *
     * @param code    事件码
     * @param payload 事件负载，可为空
     * @return 展示文案与模型事实句
     */
    public static Entry resolve(String code, Map<String, Object> payload) {
        EntryBuilder builder = ENTRIES.get(code);
        if (builder == null) {
            throw new ServiceException("未知的系统事件：" + code);
        }
        return builder.build(payload == null ? Map.of() : payload);
    }

    private static Entry draftAdopted(Map<String, Object> payload) {
        String code = text(payload, "componentCode");
        String name = text(payload, "componentName");
        String version = text(payload, "version");
        String label = name.isEmpty() ? code : name + "（" + code + "）";
        String versionSuffix = version.isEmpty() ? "" : "，版本 " + version;
        String display = "组件「" + label + "」已采纳" + versionSuffix;
        String fact = "【系统事件】组件「" + label + "」已在审阅页采纳" + versionSuffix
            + "。草稿已生效，可以继续用组件引用写链路草稿、选好 connectionId 后试跑。";
        return new Entry(display, fact);
    }

    private static Entry draftRejected(Map<String, Object> payload) {
        String code = text(payload, "componentCode");
        String display = "组件草稿「" + code + "」已被拒绝";
        String fact = "【系统事件】组件草稿「" + code + "」已在审阅页被拒绝并销毁，未生效、链路里不能引用。"
            + "请先问用户：改源码重做，还是换现成组件。";
        return new Entry(display, fact);
    }

    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
