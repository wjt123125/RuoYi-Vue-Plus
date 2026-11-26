/**
 * 业务服务层的域内协作者，按子域分 {@code chain}／{@code component}／{@code connector}。
 * <p>
 * 入口类留在 {@code service.impl}，本包只放事务内的单步逻辑，后缀按职责取：
 * {@code *Support}（领域步骤）、{@code *Reader}（只读产出）、
 * {@code *Publisher}（底层推送通道）。
 *
 * @author databus
 */
package org.dromara.databus.service.support;