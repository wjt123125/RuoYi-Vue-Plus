package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * PROCESS_START 端点入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.ProcessStartRequest} 字段一致）。
 *
 * <p>所有字段从 HTTP 请求体显式传入（决策 1：上下文原子化）。
 * title 已是替换好 ${} 模板的纯字符串（决策 9.1.6：模板替换由总线层 Component 调
 * ctx.resolveMixedPath 完成，BPM 端只接收纯字符串）。
 *
 * @author databus
 */
@Data
public class ProcessStartRequest {

    /** 流程定义 ID，必填 */
    private String processDefId;

    /** 创建人用户 ID，必填 */
    private String uid;

    /** 流程标题（已替换 ${} 模板的纯字符串），必填 */
    private String title;
}
