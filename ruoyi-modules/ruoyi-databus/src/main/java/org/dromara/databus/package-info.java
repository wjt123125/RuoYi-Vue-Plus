/**
 * 数据总线模块根包：基于 LiteFlow 的链路编排与执行引擎。
 * <p>
 * 子包划分：
 * <ul>
 *   <li>{@code controller} - REST 接口层</li>
 *   <li>{@code service} - 业务服务层</li>
 *   <li>{@code component} - LiteFlow 组件实现</li>
 *   <li>{@code connector} - 连接器协议层（HTTP/DB/MQ 等），含 {@code bpm} 子包占位</li>
 *   <li>{@code domain} - 实体、BO、VO</li>
 *   <li>{@code mapper} - MyBatis Plus Mapper</li>
 *   <li>{@code el} - EL 表达式解析与画布数据双向转换</li>
 *   <li>{@code monitor} - 指标采集、聚合与告警</li>
 * </ul>
 *
 * @author databus
 */
package org.dromara.databus;
