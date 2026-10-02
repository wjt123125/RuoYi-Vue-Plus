package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * PROCESS_START 组件（processStart）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "processDefId": "proc-001",          // 必填
 *   "uid": "admin",                     // 必填
 *   "title": "采购申请-{{ $.request.code }}" // 必填，支持 {{ $.xxx }} 表达式
 * }
 * </pre>
 * title 含 {@code {{ $.xxx }}} 表达式时由组件调 {@code resolveMixedPath} 求值拼接为字符串后传入 BPM 端
 * （模板替换在 Component 层完成，BPM 端只接收纯字符串）。
 *
 * @author databus
 */
@Data
public class ProcessStartCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 流程定义 ID（必填） */
    @DatabusProp(label = "流程定义 ID", required = true, order = 2)
    private String processDefId;

    /** 创建人用户 ID（必填，构造 UserContext） */
    @DatabusProp(label = "创建人 ID", required = true, order = 3)
    private String uid;

    /**
     * 流程标题（必填）。支持 {@code {{ $.xxx }}} 表达式，组件解析后传入 BPM 端。
     */
    @DatabusProp(
        label = "流程标题", required = true, exprRole = ExprRole.DATA, order = 4,
        description = "支持 {{ $.路径 }} 表达式拼接"
    )
    private String title;
}
