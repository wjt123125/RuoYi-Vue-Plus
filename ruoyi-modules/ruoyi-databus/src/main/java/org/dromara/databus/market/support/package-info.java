/**
 * 市场模块的域内协作者。
 * <p>
 * 本包只放「不对外暴露、供市场若干 Service 复用」的协作者，后缀按职责取：
 * {@code *Support}（领域步骤）、{@code *Loader}（只读装载）、
 * {@code *Assembler}（纯对象转换）、{@code *Text}（字符串工具）、
 * {@code *Redirector}（安装期引用改写）。
 * 数据载体（record）不放这里，在 {@link org.dromara.databus.market.model}。
 *
 * @author databus
 */
package org.dromara.databus.market.support;