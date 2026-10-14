package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线告警规则类型（databus_alert_rule.rule_type）。
 * <p>
 * 两类规则都以「目标链路 + 时间窗口（window_minutes）」为评估范围：
 * <ul>
 *   <li>{@link #CONSECUTIVE_FAILURE}：窗口内最近 threshold_count 次执行全部失败即触发；</li>
 *   <li>{@link #SUCCESS_RATE}：窗口内成功率低于 threshold_rate（百分比）即触发。</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AlertRuleTypeEnum {

    /**
     * 连续失败：窗口内最近 N 次执行全部失败
     */
    CONSECUTIVE_FAILURE("CONSECUTIVE_FAILURE", "连续失败"),

    /**
     * 成功率低于阈值：窗口内成功率低于指定百分比
     */
    SUCCESS_RATE("SUCCESS_RATE", "成功率低于阈值");

    /**
     * 类型编码。
     */
    private final String code;

    /**
     * 类型说明。
     */
    private final String info;

}