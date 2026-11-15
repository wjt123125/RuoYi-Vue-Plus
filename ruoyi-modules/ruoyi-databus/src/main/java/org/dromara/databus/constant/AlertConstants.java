package org.dromara.databus.constant;

/**
 * 告警域常量：派发状态、Webhook 签名头与默认聚合窗口。
 *
 * @author databus
 */
public final class AlertConstants {

    private AlertConstants() {
    }

    /**
     * 告警已派发
     */
    public static final String STATUS_DISPATCHED = "DISPATCHED";

    /**
     * Webhook 签名头
     */
    public static final String SIGNATURE_HEADER = "X-Databus-Signature";

    /**
     * 默认聚合窗口（分钟）
     */
    public static final int DEFAULT_WINDOW_MINUTES = 5;
}
