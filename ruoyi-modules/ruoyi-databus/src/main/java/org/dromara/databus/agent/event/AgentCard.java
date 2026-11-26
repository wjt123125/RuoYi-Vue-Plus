package org.dromara.databus.agent.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.dromara.databus.constant.AgentConstants.CARD_PROTOCOL_VERSION;
import static org.dromara.databus.constant.AgentConstants.KIND_CHOICE;
import static org.dromara.databus.constant.AgentConstants.KIND_CONFIRM;
import static org.dromara.databus.constant.AgentConstants.KIND_REF;
import static org.dromara.databus.constant.AgentConstants.PRESENTATION_DIALOG;
import static org.dromara.databus.constant.AgentConstants.PRESENTATION_INLINE;

/**
 * 通用交互卡片（运行时 §16）：一张「信封」+ 按 kind 分开的内容。
 * <p>
 * 一套协议三个来源：审批拦截由后端规则预填（{@code KIND_CONFIRM}），选择卡由模型调用
 * {@code databus_ask_user} 生成（{@code KIND_CHOICE}），工具落完草稿后补一张资源卡（{@code KIND_REF}）。
 * 三者共用同一条投递链路（SSE {@code card} 事件 + {@code databus_agent_message} 落库），
 * 所以外层保持同一个信封，内容按 kind 收进 {@link Payload}。
 * <p>
 * JSON 仍是扁平结构：{@code payload} 由 {@link JsonIgnore} 挡在序列化之外，字段经下方访问器铺平，
 * 前端与历史数据不需要感知信封。这里只用 jackson-annotations，不绑定具体 databind 版本
 * （运行时的 Mapper 是 Jackson 3，自定义 Jackson 2 序列化器会被直接忽略）。
 *
 * @param cardId       卡片 id，回执按它定位
 * @param kind         confirm / choice / ref，必须与 payload.kind() 一致
 * @param title        标题
 * @param body         正文，可空
 * @param presentation inline 留在消息流 / dialog 浮起来等回执（选择卡固定 dialog）
 * @param v            协议版本，缺省按 1 处理
 * @param payload      按 kind 分开的内容
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentCard(String cardId, String kind, String title, String body,
                        String presentation, Integer v, Payload payload) {

    public AgentCard {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }
        if (!payload.kind().equals(kind)) {
            throw new IllegalArgumentException("kind 与 payload 不一致：" + kind + " / " + payload.kind());
        }
        presentation = presentation == null ? PRESENTATION_INLINE : presentation;
        v = v == null ? CARD_PROTOCOL_VERSION : v;
    }

    /**
     * 确认卡：真正要执行的那次调用原文放在 {@code detail} 里。
     *
     * @param cardId  卡片 id
     * @param title   标题
     * @param body    影响摘要
     * @param detail  原文块
     * @param options 选项（confirm / cancel）
     * @return 确认卡
     */
    public static AgentCard confirm(String cardId, String title, String body, String detail,
                                    List<Option> options) {
        return new AgentCard(cardId, KIND_CONFIRM, title, body, PRESENTATION_INLINE,
            CARD_PROTOCOL_VERSION, new ConfirmPayload(detail, options));
    }

    /**
     * 选择卡：单选 / 多选 / 自由输入。
     *
     * @param cardId        卡片 id
     * @param title         问题正文
     * @param options       选项
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入
     * @return 选择卡
     */
    public static AgentCard choice(String cardId, String title, List<Option> options,
                                   boolean multiSelect, boolean allowFreeText) {
        return new AgentCard(cardId, KIND_CHOICE, title, null, PRESENTATION_DIALOG,
            CARD_PROTOCOL_VERSION, new ChoicePayload(options, multiSelect, allowFreeText));
    }

    /**
     * 资源卡：整卡点击由前端宿主开对应 tab。
     *
     * @param cardId   卡片 id
     * @param title    标题
     * @param body     描述
     * @param resource 指向的资源
     * @return 资源卡
     */
    public static AgentCard ref(String cardId, String title, String body, Resource resource) {
        return new AgentCard(cardId, KIND_REF, title, body, PRESENTATION_INLINE,
            CARD_PROTOCOL_VERSION, new RefPayload(resource));
    }

    /**
     * 信封本体不进 JSON：线上只认铺平后的字段。
     *
     * @return 按 kind 分开的内容
     */
    @JsonIgnore
    @Override
    public Payload payload() {
        return payload;
    }

    /**
     * 原文块：仅确认卡有。
     *
     * @return 待执行调用原文，非确认卡为 null
     */
    @JsonProperty("detail")
    public String detail() {
        return payload instanceof ConfirmPayload confirm ? confirm.detail() : null;
    }

    /**
     * 选项列表：确认卡 / 选择卡取各自 payload，资源卡固定空数组。
     *
     * @return 选项
     */
    @JsonProperty("options")
    public List<Option> options() {
        return switch (payload) {
            case ConfirmPayload confirm -> confirm.options() == null ? List.of() : confirm.options();
            case ChoicePayload choice -> choice.options() == null ? List.of() : choice.options();
            case RefPayload ignored -> List.of();
        };
    }

    /**
     * 是否多选：只有选择卡可能为 true，确认卡固定 false（与历史 JSON 一致）。
     *
     * @return 是否多选，资源卡为 null
     */
    @JsonProperty("multiSelect")
    public Boolean multiSelect() {
        return switch (payload) {
            case ChoicePayload choice -> Boolean.TRUE.equals(choice.multiSelect());
            case ConfirmPayload ignored -> false;
            case RefPayload ignored -> null;
        };
    }

    /**
     * 是否允许自由输入：仅选择卡有。
     *
     * @return 是否允许自由输入
     */
    @JsonProperty("allowFreeText")
    public Boolean allowFreeText() {
        return payload instanceof ChoicePayload choice ? Boolean.TRUE.equals(choice.allowFreeText()) : null;
    }

    /**
     * 资源指向：仅资源卡有。
     *
     * @return 资源，非资源卡为 null
     */
    @JsonProperty("resource")
    public Resource resource() {
        return payload instanceof RefPayload ref ? ref.resource() : null;
    }

    /**
     * 反序列化：以扁平字段为主（落库与推送都是这个形状），同时兼容落成嵌套 payload 的历史行。
     * <p>
     * 用整棵树做入参，而不是一列命名参数：{@code payload} 的序列化被 {@link JsonIgnore} 挡住，
     * 命名参数会因此读不到它，历史行就还原不出来。
     *
     * @param node 卡片 JSON
     * @return 卡片
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgentCard fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("卡片必须是 JSON 对象");
        }
        String kind = text(node, "kind");
        if (kind == null) {
            throw new IllegalArgumentException("卡片缺少 kind");
        }
        JsonNode nested = node.get("payload");
        Payload payload = nested != null && nested.isObject()
            ? payloadOfNode(kind, nested)
            : payloadOf(kind, text(node, "detail"), optionsOf(node), booleanOf(node, "multiSelect"),
                booleanOf(node, "allowFreeText"), resourceOf(node));
        JsonNode version = node.get("v");
        return new AgentCard(text(node, "cardId"), kind, text(node, "title"), text(node, "body"),
            text(node, "presentation"),
            version == null || version.isNull() ? CARD_PROTOCOL_VERSION : version.asInt(),
            payload);
    }

    /**
     * 扁平字段组内容。
     *
     * @param kind          卡片类型
     * @param detail        原文块
     * @param options       选项
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入
     * @param resource      资源
     * @return 卡片内容
     */
    private static Payload payloadOf(String kind, String detail, List<Option> options, Boolean multiSelect,
                                     Boolean allowFreeText, Resource resource) {
        return switch (kind) {
            case KIND_CONFIRM -> new ConfirmPayload(detail, options);
            case KIND_CHOICE -> new ChoicePayload(options, multiSelect, allowFreeText);
            case KIND_REF -> new RefPayload(resource);
            default -> throw new IllegalArgumentException("未知卡片类型：" + kind);
        };
    }

    /**
     * 嵌套 payload 组内容（兼容历史行）。
     *
     * @param kind    卡片类型
     * @param payload 嵌套内容
     * @return 卡片内容
     */
    private static Payload payloadOfNode(String kind, JsonNode payload) {
        return switch (kind) {
            case KIND_CONFIRM -> new ConfirmPayload(text(payload, "detail"), optionsOf(payload));
            case KIND_CHOICE -> new ChoicePayload(optionsOf(payload),
                booleanOf(payload, "multiSelect"), booleanOf(payload, "allowFreeText"));
            case KIND_REF -> new RefPayload(resourceOf(payload));
            default -> throw new IllegalArgumentException("未知卡片类型：" + kind);
        };
    }

    private static List<Option> optionsOf(JsonNode node) {
        JsonNode array = node.get("options");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<Option> options = new ArrayList<>();
        for (JsonNode item : array) {
            options.add(new Option(text(item, "key"), text(item, "label"),
                text(item, "description"), text(item, "style")));
        }
        return options;
    }

    private static Resource resourceOf(JsonNode node) {
        JsonNode resource = node.get("resource");
        if (resource == null || !resource.isObject()) {
            return null;
        }
        return new Resource(text(resource, "type"), text(resource, "id"), text(resource, "code"),
            text(resource, "name"), booleanOf(resource, "draft"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Boolean booleanOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asBoolean();
    }

    /**
     * 卡片内容：按 kind 分开，避免信封变成「只有某种卡才用」的字段杂物抽屉。
     */
    public sealed interface Payload permits ConfirmPayload, ChoicePayload, RefPayload {

        /**
         * 与信封 kind 一致的判别值。
         *
         * @return confirm / choice / ref
         */
        String kind();
    }

    /**
     * 确认卡内容。
     *
     * @param detail  真正要执行的那次调用原文
     * @param options 选项（confirm / cancel）
     */
    public record ConfirmPayload(String detail, List<Option> options) implements Payload {

        @Override
        public String kind() {
            return KIND_CONFIRM;
        }
    }

    /**
     * 选择卡内容。
     *
     * @param options       选项
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入（相当于「E 选项」）
     */
    public record ChoicePayload(List<Option> options, Boolean multiSelect,
                                Boolean allowFreeText) implements Payload {

        @Override
        public String kind() {
            return KIND_CHOICE;
        }
    }

    /**
     * 资源卡内容。
     *
     * @param resource 指向的资源，点击由前端宿主开 tab
     */
    public record RefPayload(Resource resource) implements Payload {

        @Override
        public String kind() {
            return KIND_REF;
        }
    }

    /**
     * 卡片选项。
     *
     * @param key         回执动作键
     * @param label       按钮文案
     * @param description 说明点了会发生什么
     * @param style       保留字段，primary 用于确认主按钮
     */
    public record Option(String key, String label, String description, String style) {
    }

    /**
     * 资源卡指向的资源。
     *
     * @param type  component / chain
     * @param id    资源 id，雪花 id 走字符串
     * @param code  编码
     * @param name  名称
     * @param draft 是否仍是草稿（仅 component）
     */
    public record Resource(String type, String id, String code, String name, Boolean draft) {
    }
}
