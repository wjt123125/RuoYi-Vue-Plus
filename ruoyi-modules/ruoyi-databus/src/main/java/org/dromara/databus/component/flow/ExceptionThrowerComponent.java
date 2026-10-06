package org.dromara.databus.component.flow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.component.cfg.ExceptionThrowerCfg;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;

/**
 * 异常抛出组件（注册名 {@code exceptionThrower}）。
 * <p>
 * 「故障制造机」：运行到本节点时反射构造并抛出配置的异常，用于在纯本地链路中
 * 制造可控故障，配合 CATCH 测试不同异常类型的兜底分流。<b>生产链路不应放置本组件。</b>
 * <p>
 * 目标类约束：必须是 {@link Throwable} 子类，且具备 {@code (String message)} 公有构造器；
 * 类名/构造器不满足时抛带修复指引的配置错误（ServiceException），不做静默兜底。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("exceptionThrower")
@DatabusCmp(
    code = "exceptionThrower",
    name = "异常抛出",
    icon = "ph:warning",
    color = "#f56c6c",
    group = "other",
    sort = 50,
    description = "测试用故障制造机：按异常类名（常量或 {{ $.路径 }}）反射抛出异常，配合 CATCH 验证异常兜底链；生产链路勿用",
    cfg = ExceptionThrowerCfg.class,
    dataExample = """
        {"exceptionClass":"{{ $.exceptionClass }}","message":"模拟参数校验失败"}
        """
)
public class ExceptionThrowerComponent extends DatabusNodeComponent {

    @Override
    public void process() throws Exception {
        ExceptionThrowerCfg cfg = this.getCmpData(ExceptionThrowerCfg.class);
        if (cfg == null || cfg.getExceptionClass() == null || cfg.getExceptionClass().isBlank()) {
            throw new ServiceException("异常抛出组件缺少 exceptionClass 配置（tag=" + this.getTag() + "）");
        }

        Object classResolved = resolveParam(cfg.getExceptionClass().trim());
        if (classResolved == null || classResolved.toString().isBlank()) {
            throw new ServiceException("异常抛出组件 exceptionClass 求值为空（tag=" + this.getTag() + "）："
                + cfg.getExceptionClass());
        }
        String className = classResolved.toString().trim();

        Object messageResolved = cfg.getMessage() == null ? null : resolveParam(cfg.getMessage());
        String message = messageResolved == null ? null : messageResolved.toString();

        Class<?> clazz;
        try {
            clazz = ClassUtils.forName(className, ClassUtils.getDefaultClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ServiceException("异常抛出组件配置的异常类不存在：" + className
                + "（请检查全限定类名，tag=" + this.getTag() + "）");
        }
        if (!Throwable.class.isAssignableFrom(clazz)) {
            throw new ServiceException("异常抛出组件配置的类不是 Throwable 子类：" + className
                + "（tag=" + this.getTag() + "）");
        }

        Constructor<?> constructor;
        try {
            constructor = clazz.getConstructor(String.class);
        } catch (NoSuchMethodException e) {
            throw new ServiceException("异常抛出组件配置的异常类缺少 (String message) 公有构造器：" + className
                + "（tag=" + this.getTag() + "）");
        }

        Throwable throwable = (Throwable) constructor.newInstance(message);
        log.info("[databus] exceptionThrower 主动抛出异常 tag={}, class={}", this.getTag(), className);
        // NodeComponent.process 声明 throws Exception：Exception 直接抛，Error 是非检查异常同样可抛
        if (throwable instanceof Exception exception) {
            throw exception;
        }
        throw (Error) throwable;
    }
}
