package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;

/**
 * 异常抛出组件（exceptionThrower）的节点参数。
 * <p>
 * 纯本地链路没有真实故障源，本组件充当「故障制造机」：运行到该节点时按配置抛出指定异常，
 * 供 CATCH 异常处理链测试/演示使用（生产链路不应放置）。
 * <pre>
 * {
 *   "exceptionClass": "{{ $.exceptionClass }}",
 *   "message": "模拟参数校验失败"
 * }
 * </pre>
 *
 * @author databus
 */
@Data
public class ExceptionThrowerCfg {

    /**
     * 异常全限定类名（要数据字段）：常量（如 {@code java.lang.IllegalArgumentException}）
     * 或 {@code {{ $.路径 }}} 表达式从数据空间取类名，便于一条链换入参测多种异常。
     * 目标类必须是 {@link Throwable} 子类且具备 {@code (String message)} 构造器。
     */
    @DatabusProp(label = "异常类名", required = true, order = 1, exprRole = ExprRole.DATA,
        description = "Throwable 子类的全限定类名，可写常量或 {{ $.路径 }} 表达式；该类必须有 (String) 构造器",
        placeholder = "java.lang.IllegalArgumentException")
    private String exceptionClass;

    /**
     * 异常消息（要数据字段）：常量或 {@code {{ $.路径 }}} 表达式。
     */
    @DatabusProp(label = "异常消息", order = 2, exprRole = ExprRole.DATA,
        placeholder = "模拟业务异常")
    private String message;
}
