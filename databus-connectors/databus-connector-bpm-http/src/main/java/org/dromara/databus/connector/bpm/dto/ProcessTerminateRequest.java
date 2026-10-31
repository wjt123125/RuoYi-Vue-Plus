package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * PROCESS_TERMINATE 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.ProcessTerminateRequest} 字段一致）。
 * <p>
 * instanceId 定位流程实例，userId 为终止操作人（SDK terminateById 签名要求）。
 *
 * @author databus
 */
@Data
public class ProcessTerminateRequest {

    /** 流程实例 ID，必填 */
    private String instanceId;

    /** 终止操作人用户 ID，必填 */
    private String userId;
}
