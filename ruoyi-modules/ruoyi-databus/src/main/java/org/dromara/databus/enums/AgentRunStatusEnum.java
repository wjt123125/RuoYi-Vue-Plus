package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI agent 任务状态（databus_agent_run.status）
 * <ul>
 *   <li>RUNNING 进行中：前端可停止，同链路新 run 会把它置 SUPERSEDED</li>
 *   <li>FINISHED 已完成：正常结束（含「未做到」的如实报告）</li>
 *   <li>FAILED 失败：模型/链路上抛错</li>
 *   <li>STOPPED 已停止：用户点停止，或前端流断开</li>
 *   <li>EXPIRED 已过期：守卫查行时发现超过 expire_time（惰性判定，无扫描任务）</li>
 *   <li>SUPERSEDED 已被取代：同链路又发起了一次 run</li>
 * </ul>
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum AgentRunStatusEnum {

    /**
     * 进行中
     */
    RUNNING("RUNNING", "进行中"),

    /**
     * 已完成
     */
    FINISHED("FINISHED", "已完成"),

    /**
     * 失败
     */
    FAILED("FAILED", "失败"),

    /**
     * 已停止
     */
    STOPPED("STOPPED", "已停止"),

    /**
     * 已过期
     */
    EXPIRED("EXPIRED", "已过期"),

    /**
     * 已被取代
     */
    SUPERSEDED("SUPERSEDED", "已被取代");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}