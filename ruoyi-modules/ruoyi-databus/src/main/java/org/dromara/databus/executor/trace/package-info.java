/**
 * 执行记录采集骨架（追踪牌模式，设计档 §4.3）：
 * <ul>
 *     <li>{@link org.dromara.databus.executor.trace.ExecutionTrace}——一次正式执行一块牌，
 *     挂在 DatabusContext 上，持记录 id/档位/入参快照/起点与 FULL 档节点行线程安全缓冲；</li>
 *     <li>{@link org.dromara.databus.executor.trace.NodeTraceRow}——节点轨迹纯数据行；</li>
 *     <li>{@link org.dromara.databus.executor.trace.ExecutionTraceRecorder}——独立事务落库
 *     （总账必达、明细尽力）。</li>
 * </ul>
 * 钩子在 executor 包：NodeStepResultCollector（节点采集）/ FlowExecutionTraceLifeCycle（总账落库）。
 *
 * @author databus
 */
package org.dromara.databus.executor.trace;
