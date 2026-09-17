package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * PROCESS_START 请求入参（对应 BPM 端 {@code ProcessStartRequest}）。
 * <p>
 * 所有字段从 Component 层显式传入（决策 §1：上下文原子化）。
 * <p>
 * {@code title} 已是替换好 {@code ${}} 模板的纯字符串（决策 §9.1.6：模板替换由 Component 层
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

    /** 流程标题（已替换 ${} 模板的纯字符串），必填 */
    private String title;
}
