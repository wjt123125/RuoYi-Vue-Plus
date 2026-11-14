package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 会话级操作审批模式（databus_agent_approval_setting.mode）
 * <ul>
 *   <li>MANUAL 手动审批：每次试跑前问，未同意不执行</li>
 *   <li>AUTO 自动审批：规则判定，纯只读节点直接跑，含写/外部/未知节点要问</li>
 *   <li>FULL 完全访问：直接跑；部署开关 databus.agent.allow-full-access 未开时按 MANUAL 处理</li>
 *   <li>CUSTOM 自定义：暂未开放，前端置灰；历史脏值按 MANUAL 处理</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AgentApprovalModeEnum {

    /**
     * 手动审批
     */
    MANUAL("MANUAL", "手动审批"),

    /**
     * 自动审批
     */
    AUTO("AUTO", "自动审批"),

    /**
     * 完全访问
     */
    FULL("FULL", "完全访问"),

    /**
     * 自定义（暂未开放）
     */
    CUSTOM("CUSTOM", "自定义");

    /**
     * 模式编码。
     */
    private final String code;

    /**
     * 模式文案。
     */
    private final String info;

    /**
     * 按编码解析，空值或未知编码一律回落 MANUAL（保守）。
     *
     * @param code 模式编码
     * @return 模式，永不为 null
     */
    public static AgentApprovalModeEnum of(String code) {
        if (code != null) {
            for (AgentApprovalModeEnum mode : values()) {
                if (mode.code.equalsIgnoreCase(code.trim())) {
                    return mode;
                }
            }
        }
        return MANUAL;
    }

}