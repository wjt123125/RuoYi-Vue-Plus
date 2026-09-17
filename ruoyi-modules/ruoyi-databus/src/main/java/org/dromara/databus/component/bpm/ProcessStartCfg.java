package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * PROCESS_START 组件（processStart）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "processDefId": "proc-001",          // 必填
 *   "uid": "admin",                     // 必填
 *   "title": "采购申请-${$.request.code}" // 必填，支持 ${$.xxx} 模板替换
 * }
 * </pre>
 * title 含 {@code ${$.xxx}} 模板时由组件调 {@code resolveMixedPath} 替换为纯字符串后传入 BPM 端
 * （决策 9.1.6：模板替换在 Component 层完成，BPM 端只接收纯字符串）。
 *
 * @author databus
 */
@Data
public class ProcessStartCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 流程定义 ID（必填） */
    private String processDefId;

    /** 创建人用户 ID（必填，构造 UserContext） */
    private String uid;

    /**
     * 流程标题（必填）。支持 {@code ${$.xxx}} 模板，组件解析后传入 BPM 端。
     */
    private String title;
}
