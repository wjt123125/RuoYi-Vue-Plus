package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * TASK_COMPLETE 端点入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.TaskCompleteRequest} 字段一致）。
 *
 * <p>所有字段从 HTTP 请求体显式传入（决策 1：上下文原子化）。
 *
 * @author databus
 */
@Data
public class TaskCompleteRequest {

    /** 流程实例 ID，必填 */
    private String processInstanceId;

    /** 提交人用户 ID，必填（用于构造 UserContext） */
    private String uid;
}
