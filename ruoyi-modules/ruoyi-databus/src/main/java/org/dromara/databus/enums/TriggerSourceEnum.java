package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 链路执行触发来源（databus_execution.trigger_source）。
 * <p>
 * 存在的唯一硬理由是<b>告警防死循环</b>：CHAIN 渠道的告警通知链自己也是一次正式执行，
 * 也会落总账；若它执行失败又被告警规则评估命中，就会「告警→通知链→失败→再告警→再通知」
 * 无限打转。告警评估查询统一排除 {@link #ALERT}，从数据源头掐断这条回路。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum TriggerSourceEnum {

    /**
     * 页面手动执行（链路工作台 / 执行记录重跑）
     */
    MANUAL("MANUAL", "手动执行"),

    /**
     * 外部接口调用（对外开放 API）
     */
    API("API", "接口调用"),

    /**
     * 告警通知链触发（不参与告警评估）
     */
    ALERT("ALERT", "告警通知");

    /**
     * 来源编码。
     */
    private final String code;

    /**
     * 来源说明。
     */
    private final String info;

}
