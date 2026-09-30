package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线执行状态（databus_execution.status / databus_execution_node.status）
 * <p>
 * 执行级总账三态：执行开始即落 RUNNING（当前实现流程结束后一次性落库，故实际只见终态，
 * RUNNING 预留给将来异步/中断场景），结束按 LiteFlow 响应置 SUCCESS / FAILED；
 * 节点级明细只用 SUCCESS / FAILED（after 钩子按异常参数判定）。
 *
 * @author databus

 */
@Getter
@AllArgsConstructor
public enum ExecutionStatusEnum {

    /**
     * 进行中
     */
    RUNNING("RUNNING", "进行中"),

    /**
     * 成功
     */
    SUCCESS("SUCCESS", "成功"),

    /**
     * 失败
     */
    FAILED("FAILED", "失败");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}
