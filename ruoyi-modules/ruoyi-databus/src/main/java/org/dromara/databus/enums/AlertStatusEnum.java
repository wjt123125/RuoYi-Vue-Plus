package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线告警记录状态（databus_alert_record.status）。
 * <p>
 * 一次告警生命周期一行：触发时置 {@link #FIRING}，恢复时翻转为 {@link #RECOVERED} 并回填恢复时间；
 * 持续触发/持续正常期间状态不变（评估任务靠此去重，避免重复通知）。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AlertStatusEnum {

    /**
     * 触发中
     */
    FIRING("FIRING", "触发中"),

    /**
     * 已恢复
     */
    RECOVERED("RECOVERED", "已恢复");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}