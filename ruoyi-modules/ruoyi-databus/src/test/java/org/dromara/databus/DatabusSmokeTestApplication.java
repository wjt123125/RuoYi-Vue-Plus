package org.dromara.databus;

import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import org.dromara.common.liteflow.config.LiteFlowAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

/**
 * 冒烟测试专用启动类。
 * <p>
 * 通过 {@link ImportAutoConfiguration} 仅导入 LiteFlow 相关的三个自动装配类
 * （{@link LiteflowPropertyAutoConfiguration}、{@link LiteflowMainAutoConfiguration}、
 * {@link LiteFlowAutoConfiguration}），不触发 ruoyi-common-mybatis / redis / sa-token 等
 * 自动装配，避免在没有数据库、Redis 的测试环境下加载失败。
 * <p>
 * 组件扫描覆盖 {@code org.dromara.databus}，自动发现测试目录下的
 * {@code @LiteflowComponent} 组件。
 * <p>
 * 排除 Web 层（@RestController/@Controller）与 Service 层（@Service）：
 * Controller/Service 依赖 Mapper，而本上下文刻意不装配 mybatis；
 * EL 生成/校验测试只需要 ExpressGenerator 及其 parser，无需业务 Service。
 *
 * @author databus
 */
@Configuration
@ComponentScan(value = "org.dromara.databus",
    excludeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
        classes = {RestController.class, Controller.class, Service.class}))
@ImportAutoConfiguration({
    LiteflowPropertyAutoConfiguration.class,
    LiteflowMainAutoConfiguration.class,
    LiteFlowAutoConfiguration.class
})
public class DatabusSmokeTestApplication {

}
