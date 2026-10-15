package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线告警通知总结果（databus_alert_record.notify_status）。
 * <p>
 * 一条告警可能同时走多个渠道，本字段是多渠道的汇总口径，逐渠道明细见
 * {@code notify_detail}（JSON）。便于排查「规则命中了但没收到通知」：
 * 邮件通道未启用、SMTP 异常、Webhook 非 2xx、通知链未发布都落到 {@link #FAILED}。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AlertNotifyStatusEnum {

    /**
     * 全部渠道发送成功
     */
    SUCCESS("SUCCESS", "发送成功"),

    /**
     * 部分渠道失败（明细见 notify_detail）
     */
    PARTIAL("PARTIAL", "部分成功"),

    /**
     * 全部渠道失败（通道未启用 / 配置缺失 / 调用异常）
     */
    FAILED("FAILED", "发送失败"),

    /**
     * 未发送（规则未勾选任何通知渠道）
     */
    SKIPPED("SKIPPED", "未发送");

    /**
     * 结果编码。
     */
    private final String code;

    /**
     * 结果说明。
     */
    private final String info;

}