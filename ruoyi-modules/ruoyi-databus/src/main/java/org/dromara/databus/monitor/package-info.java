/**
 * 监控子域导航：指标采集、按小时/天聚合、告警引擎。阶段5 落地。
 * <p>
 * 实现分散在 {@code alert}（告警评估与通知）、{@code job}（聚合与清理定时任务）、
 * {@code service.impl.DatabusMetricServiceImpl}（指标查询）；本包只作分组与说明。
 *
 * @author databus
 */
package org.dromara.databus.monitor;