package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * PROCESS_TERMINATE 组件（processTerminate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",                          // 必填
 *   "instanceId": "$.processStart1.processInstanceId",      // 必填，流程实例 ID（支持路径引用）
 *   "userId": "admin"                                       // 必填，终止操作人（支持路径引用）
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

    /** 流程实例 ID（必填，支持路径引用） */
    private String instanceId;

    /** 终止操作人用户 ID（必填，支持路径引用） */
    private String userId;
}
