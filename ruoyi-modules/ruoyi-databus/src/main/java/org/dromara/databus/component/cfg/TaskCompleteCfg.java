package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * TASK_COMPLETE 组件（taskComplete）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "processInstanceId": "{{ $.processStart1.processInstanceId }}",  // 必填
 *   "uid": "admin",                                            // 必填
 *   "failOnError": false                                       // 可选，默认 false
 * }
 * </pre>
 * failOnError 控制 BPM 端返回部分失败（failedTaskIds 非空）时是否抛异常：
 * <ul>
 *   <li>true：抛 ServiceException 中断后续流程</li>
 *   <li>false：仅 log.warn 不抛，调用方可在后续节点读 {@code $.<tag>.failedTaskIds} 自行决策</li>
 * </ul>
 * 决策 9.1.3：TASK_COMPLETE 全部尝试 + failedTaskIds 记录 + code 非 0 表示部分失败。
 *
 * @author databus
 */
@Data
public class TaskCompleteCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 流程实例 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(
        label = "流程实例 ID", required = true, exprRole = ExprRole.DATA, order = 2,
        placeholder = "{{ $.processStart1.processInstanceId }}"
    )
    private String processInstanceId;

    /** 提交人用户 ID（必填，构造 UserContext） */
    @DatabusProp(label = "提交人 ID", required = true, order = 3)
    private String uid;

    /** 部分失败时是否抛异常（可选，默认 false：仅 warn 不抛） */
    @DatabusProp(
        label = "部分失败时中断流程", widget = WidgetKind.BOOLEAN, order = 4,
        description = "开启：任一任务失败即抛异常中断；关闭（默认）：仅告警，失败任务 ID 写入 $.<tag>.failedTaskIds"
    )
    private Boolean failOnError;
}
