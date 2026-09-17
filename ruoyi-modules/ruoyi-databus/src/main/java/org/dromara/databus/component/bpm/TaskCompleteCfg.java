package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * TASK_COMPLETE 组件（taskComplete）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "processInstanceId": "$.processStart1.processInstanceId",  // 必填
 *   "uid": "admin",                                            // 必填
 *   "failOnError": false                                       // 可选，默认 false
 * }
 * </pre>
 * failOnError 控制 BPM 端返回部分失败（failedTaskIds 非空）时是否抛异常：
 * <ul>
 *   <li>true：抛 ServiceException 中断后续流程</li>
 *   <li>false：仅 log.warn 不抛，调用方可在后续节点读 {@code $.<tag>.failedTaskIds} 自行决策</li>
 * </ul>
 * 决策 9.1.3：TASK_COMPLETE 全部尝试 + failedTaskIds 记录 + code 非 0 表示部分失败。
 *
 * @author databus
 */
@Data
public class TaskCompleteCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 流程实例 ID（必填） */
    private String processInstanceId;

    /** 提交人用户 ID（必填，构造 UserContext） */
    private String uid;

    /** 部分失败时是否抛异常（可选，默认 false：仅 warn 不抛） */
    private Boolean failOnError;
}
