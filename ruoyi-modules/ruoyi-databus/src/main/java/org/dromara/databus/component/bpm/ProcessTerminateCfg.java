package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * PROCESS_TERMINATE 组件（processTerminate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",                          // 必填
 *   "instanceId": "{{ $.processStart1.processInstanceId }}", // 必填，流程实例 ID（字面量或表达式）
 *   "userId": "admin"                                       // 必填，终止操作人（字面量或表达式）
 * }
 * </pre>
 *
 * <p>响应存 {@code $.<tag>.result} = {processInstanceId, terminated, alreadyEnded}；
 * 流程已结束时 terminated=false + alreadyEnded=true（幂等，不报错）。
 *
 * @author databus
 */
@Data
public class ProcessTerminateCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 流程实例 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    private String instanceId;

    /** 终止操作人用户 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    private String userId;
}
