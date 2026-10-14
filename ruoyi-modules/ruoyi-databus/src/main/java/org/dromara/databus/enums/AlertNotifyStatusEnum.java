package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线告警通知结果（databus_alert_record.notify_status）。
 * <p>
 * 记录触发通知（邮件）的发送结果，便于排查「规则命中了但没收到邮件」：
 * 邮件通道未启用（mail.enabled=false）或 SMTP 异常都会落到 {@link #FAILED}。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AlertNotifyStatusEnum {

    /**
     * 发送成功
     */
    SUCCESS("SUCCESS", "发送成功"),

    /**
     * 发送失败（通道未启用或 SMTP 异常）
     */
    FAILED("FAILED", "发送失败"),

    /**
     * 未发送（规则未配置收件人）
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