package org.dromara.databus.agent;

import org.dromara.databus.constant.AgentConstants;

import java.util.List;

/**
 * 通用交互卡片（运行时 §16）。
 * <p>
 * 一套协议三个来源：审批拦截由后端规则预填（{@link AgentConstants#KIND_CONFIRM}），后续选择卡由模型调用
 * {@code databus_ask_user} 生成（{@link AgentConstants#KIND_CHOICE}），工具落完草稿后补一张资源卡
 * （{@link AgentConstants#KIND_REF}）给人留可点入口。前端只认卡片结构，不关心来源。
 * 卡片随事件流推送，同时以 {@code event_code=card} 落 {@code databus_agent_message} 供刷新回放。
 *
 * @param cardId       run 内唯一标识，用户点选后原样回传
 * @param kind         {@link AgentConstants#KIND_CONFIRM} / {@link AgentConstants#KIND_CHOICE} / {@link AgentConstants#KIND_REF}
 * @param title        卡片标题，后端固定文案
 * @param body         事实清单或问题正文
 * @param detail       原文块：真正要执行的那次调用（等宽展示，可空）
 * @param options      可选项，每项必须带 description
 * @param multiSelect  是否多选，仅 choice 使用
 * @param presentation inline / dialog，后者留给后续选择弹窗
 * @param resource     卡片指向的资源，仅 ref 使用；点击由前端宿主开 tab
 * @author databus
 */
public record AgentCard(String cardId, String kind, String title, String body, String detail,
                        List<Option> options, Boolean multiSelect, String presentation,
                        Resource resource) {

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

    /**
     * 卡片指向的资源（ref 卡专用）。
     *
     * @param type component / chain，前端按此决定开审阅 tab 还是画布
     * @param id   主键，随 JSON 走字符串避免雪花 id 精度丢失
     * @param code 业务编码，行不在树上时前端可按 code 兜底
     * @param name 展示名
     */
    public record Resource(String type, String id, String code, String name) {
    }
}