package org.dromara.databus.alert;

import org.dromara.databus.enums.AlertNotifyTypeEnum;

/**
 * 告警通知渠道策略接口。
 * <p>
 * 一个实现 = 一种渠道（EMAIL/WEBHOOK/CHAIN/...）。Spring 收集全部实现，
 * 由 {@link AlertNotifyRouter} 按规则勾选的渠道类型分发。实现只管「怎么发」，
 * 不管「什么时候发」；发送异常直接抛出，由路由统一 catch 记为该渠道 FAILED。
 *
 * @author databus
 */
public interface AlertNotifier {

    /**
     * 渠道类型（与规则 notify_type 中的编码对应）。
     *
     * @return 渠道类型枚举
     */
    AlertNotifyTypeEnum type();

    /**
     * 发送一次通知。
     *
     * @param event     告警事件载荷（触发与恢复共用，接收方按 status 区分）
     * @param configJson 该渠道的参数 JSON（notify_config 里本渠道键对应的对象；可为 null）
     * @return 结果编码（SUCCESS / DISPATCHED 等，FAILED 由路由包装异常生成）
     * @throws Exception 参数缺失或发送失败（消息会写进 notify_detail）
     */
    String send(AlertEvent event, String configJson) throws Exception;

}
