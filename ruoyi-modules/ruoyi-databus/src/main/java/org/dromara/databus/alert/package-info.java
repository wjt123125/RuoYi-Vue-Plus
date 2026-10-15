/**
 * 告警通知：策略式多渠道分发（EMAIL / WEBHOOK / CHAIN）。
 * <p>
 * 一次告警（触发或恢复）组装一个 {@link org.dromara.databus.alert.AlertEvent}，
 * 由 {@link org.dromara.databus.alert.AlertNotifyRouter} 按规则勾选的渠道逐个分发给
 * 对应 {@link org.dromara.databus.alert.AlertNotifier} 实现，汇总逐渠道成败写回告警记录。
 * 评估引擎只管「什么时候该通知」，「怎么通知」全在本包，新增渠道不动引擎。
 *
 * @author databus
 */
package org.dromara.databus.alert;
