package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * TASK_COMPLETE 请求入参（对应 BPM 端 {@code TaskCompleteRequest}）。
 * <p>
 * 所有字段从 Component 层显式传入（决策 §1：上下文原子化）。
 *
 * @author databus
 */
@Data
public class BpmTaskCompleteParam {

    /** 流程实例 ID，必填 */
    private String processInstanceId;

    /** 提交人用户 ID，必填（用于构造 UserContext） */
    private String uid;
}
