/**
 * 流程控制类组件：FOR 计数循环 / ITERATOR 迭代循环 / SWITCH 选择路由。
 * <p>
 * 三个组件分别继承 LiteFlow 的 NodeForComponent / NodeIteratorComponent /
 * NodeSwitchComponent，作为 FOR/ITERATOR/SWITCH 算子的条件位节点使用；
 * WHILE 不需要专用组件，复用 {@code logical} 包的布尔条件组件。
 * <p>
 * 循环索引占位符（{@code $i}/{@code $j}/{@code $k}）的线程级维护见
 * {@link org.dromara.databus.context.DatabusContext} 与本包 {@code LoopSupport}，
 * 规格见 {@code docs/wiki/databus-loop-component.md}。
 *
 * @author databus
 */
package org.dromara.databus.component.flow;
