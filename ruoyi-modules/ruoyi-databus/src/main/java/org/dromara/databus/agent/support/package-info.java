/**
 * AI agent 的域内协作者：身份守卫、身份解析、错误信封组装、会话元信息。
 * <p>
 * 后缀按职责取：{@code *Support}（领域步骤）、{@code *Guard}（身份与权限守卫）、
 * {@code *Resolver}（解析与归类）。
 * 值对象与异常不放这里：{@code AgentError} 在 {@code agent.event}，
 * {@code AgentGuardRejectedException} 在 {@code agent} 根包。
 *
 * @author databus
 */
package org.dromara.databus.agent.support;