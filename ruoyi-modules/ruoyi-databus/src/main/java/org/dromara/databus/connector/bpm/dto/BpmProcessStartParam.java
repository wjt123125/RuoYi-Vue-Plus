package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * PROCESS_START 请求入参（对应 BPM 端 {@code ProcessStartRequest}）。
 * <p>
 * 所有字段从 Component 层显式传入（决策 §1：上下文原子化）。
 * <p>
 * {@code title} 已是求值拼接好的纯字符串（模板替换由 Component 层
 * 调 {@code ctx.resolveMixedPath} 完成，BPM 端只接收纯字符串）。
 *
 * @author databus
 */
@Data
public class BpmProcessStartParam {

    /** 流程定义 ID，必填 */
    private String processDefId;

    /** 创建人用户 ID，必填 */
    private String uid;

    /** 流程标题（已求值拼接的纯字符串），必填 */
    private String title;
}
