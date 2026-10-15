package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.dromara.common.core.utils.StringUtils;

/**
 * 告警通知渠道类型（databus_alert_rule.notify_type，多选逗号分隔）。
 * <p>
 * 每个类型对应一个 {@code AlertNotifier} 实现，渠道参数存在 notify_config JSON 里
 * （键为本枚举 code，值为该渠道自己的参数对象）。新增渠道 = 加一个枚举值 + 一个
 * Notifier 实现 + 前端一段子表单，表结构与路由代码不动。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AlertNotifyTypeEnum {

    /**
     * 邮件（ruoyi-common-mail，需 mail.enabled=true 且配好 SMTP）
     */
    EMAIL("EMAIL", "邮件"),

    /**
     * Webhook 回调（POST JSON 推 AlertEvent 到外部地址）
     */
    WEBHOOK("WEBHOOK", "Webhook 回调"),

    /**
     * 通知链路（异步调 DatabusExecutor 跑指定链路，链路内可做任意告警动作）
     */
    CHAIN("CHAIN", "通知链路");

    /**
     * 渠道编码。
     */
    private final String code;

    /**
     * 渠道说明。
     */
    private final String info;

    /**
     * 编码是否合法。
     *
     * @param code 渠道编码
     * @return true = 已支持的渠道
     */
    public static boolean isValid(String code) {
        if (StringUtils.isBlank(code)) {
            return false;
        }
        for (AlertNotifyTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取渠道说明（未知编码原样回退，便于日志定位脏数据）。
     *
     * @param code 渠道编码
     * @return 渠道说明
     */
    public static String infoOf(String code) {
        for (AlertNotifyTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type.info;
            }
        }
        return code;
    }

}
