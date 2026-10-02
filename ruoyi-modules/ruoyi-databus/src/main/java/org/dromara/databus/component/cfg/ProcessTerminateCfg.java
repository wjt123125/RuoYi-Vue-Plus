package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * PROCESS_TERMINATE 组件（processTerminate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",                          // 必填
 *   "instanceId": "{{ $.processStart1.processInstanceId }}", // 必填，流程实例 ID（字面量或表达式）
 *   "userId": "admin"                                       // 必填，终止操作人（字面量或表达式）
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
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 流程实例 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(
        label = "流程实例 ID", required = true, exprRole = ExprRole.DATA, order = 2,
        placeholder = "{{ $.processStart1.processInstanceId }}"
    )
    private String instanceId;

    /** 终止操作人用户 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(label = "终止操作人", required = true, exprRole = ExprRole.DATA, order = 3)
    private String userId;
}
