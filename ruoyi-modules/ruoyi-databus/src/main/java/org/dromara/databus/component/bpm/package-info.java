/**
 * BPM 业务组件：每个组件继承 {@link org.dromara.databus.component.DatabusNodeComponent}，
 * 通过 {@link org.dromara.databus.connector.bpm.BpmHttpConnector} 调 BPM 端总线 app 的对应端点。
 * <p>
 * 4 个 BPM 组件对应 BPM 端 4 个原子端点：
 * <ul>
 *   <li>{@code sessionCreate} - SESSION_CREATE 端点</li>
 *   <li>{@code boCreate} - BO_CREATE 端点（含 6 回写策略：no/all/boId/add/exclude/include）</li>
 *   <li>{@code processStart} - PROCESS_START 端点</li>
 *   <li>{@code taskComplete} - TASK_COMPLETE 端点</li>
 * </ul>
 *
 * <p>每个组件按各自 Cfg 配置（{@code connectionId} 引用 Connection）+ Connector 协议层调用，
 * 响应按字段写入数据空间 {@code $.<tag>.*}。
 *
 * @author databus
 */
package org.dromara.databus.component.bpm;
