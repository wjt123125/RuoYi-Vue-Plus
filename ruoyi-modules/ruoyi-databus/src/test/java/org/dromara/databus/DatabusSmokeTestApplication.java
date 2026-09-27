package org.dromara.databus;

import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import org.dromara.common.liteflow.config.LiteFlowAutoConfiguration;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.service.ISysDatabusConnectionService;
import org.dromara.databus.service.impl.RulePublishService;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * 排除 Web 层（@RestController/@Controller）与 Service 层：
 * Controller/Service 依赖 Mapper，而本上下文刻意不装配 mybatis；
 * EL 生成/校验测试只需要 ExpressGenerator 及其 parser，无需业务 Service。
 * <p>
 * 另有两个运行期补充（2026-09-27）：
 * <ul>
 *     <li>{@link RulePublishService} 虽是 {@code @Component} 但构造需要 {@code DataSource}
 *     与 {@code spring.application.name}，测试不连库且不走发布，按类型排除；</li>
 *     <li>{@link DatabusExecutor} 构造需要 {@link ISysDatabusConnectionService}，
 *     其实现类是 {@code @Service} 已被注解过滤，补一个 Mockito 桩（返回空连接列表）。</li>
 * </ul>
 *
 * @author databus
 */
@Configuration
@ComponentScan(value = "org.dromara.databus",
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.ANNOTATION,
            classes = {RestController.class, Controller.class, Service.class}),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
            classes = RulePublishService.class)
    })
@ImportAutoConfiguration({
    LiteflowPropertyAutoConfiguration.class,
    LiteflowMainAutoConfiguration.class,
    LiteFlowAutoConfiguration.class
})
public class DatabusSmokeTestApplication {

    /**
     * 连接服务测试桩：无数据库环境下 {@code DatabusExecutor.injectConnections} 取到空列表，
     * 语义等同于「没有启用的连接」，引擎链路（含纯本地 setValue/WHEN）不受影响。
     */
    @Bean
    public ISysDatabusConnectionService sysDatabusConnectionService() {
        ISysDatabusConnectionService connectionService = mock(ISysDatabusConnectionService.class);
        when(connectionService.loadEnabledConnections()).thenReturn(List.of());
        return connectionService;
    }

}
