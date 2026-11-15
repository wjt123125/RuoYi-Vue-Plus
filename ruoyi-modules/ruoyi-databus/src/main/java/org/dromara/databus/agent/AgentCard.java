package org.dromara.databus.agent;

import java.util.List;

/**
 * 通用交互卡片（运行时 §16）。
 * <p>
 * 一套协议两个来源：审批拦截由后端规则预填（{@link #KIND_CONFIRM}），后续选择卡由模型调用
 * {@code databus_ask_user} 生成（{@link #KIND_CHOICE}）。前端只认卡片结构，不关心来源。
 * 卡片随事件流推送，同时以 {@code event_code=card} 落 {@code databus_agent_message} 供刷新回放。
 *
 * @param cardId       run 内唯一标识，用户点选后原样回传
 * @param kind         {@link #KIND_CONFIRM} / {@link #KIND_CHOICE}
 * @param title        卡片标题，后端固定文案
 * @param body         事实清单或问题正文
 * @param detail       原文块：真正要执行的那次调用（等宽展示，可空）
 * @param options      可选项，每项必须带 description
 * @param multiSelect  是否多选，仅 choice 使用
 * @param presentation inline / dialog，后者留给后续选择弹窗
 * @author databus
 */
public record AgentCard(String cardId, String kind, String title, String body, String detail,
                        List<Option> options, Boolean multiSelect, String presentation) {

    /**
     * 确认卡：只有确认 / 取消两个动作。
     */
    public static final String KIND_CONFIRM = "confirm";

    /**
     * 选择卡：后续 ABCD 类选择复用。
     */
    public static final String KIND_CHOICE = "choice";

    /**
     * 内联渲染：跟在触发它的那条 AI 消息之后。
     */
    public static final String PRESENTATION_INLINE = "inline";

    /**
     * 卡片选项。
     *
     * @param key         回传的动作键
     * @param label       按钮文案
     * @param description 选项自述，必填
     * @param style       primary / default
     */
    public record Option(String key, String label, String description, String style) {
    }
}