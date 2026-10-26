package org.dromara.databus;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.yomahub.liteflow.springboot.config.LiteflowMainAutoConfiguration;
import com.yomahub.liteflow.springboot.config.LiteflowPropertyAutoConfiguration;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.liteflow.config.LiteFlowAutoConfiguration;
import org.dromara.databus.alert.AlertEvaluateTrigger;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.executor.FlowExecutionTraceLifeCycle;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.mapper.DatabusExecutionNodeMapper;
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

import static org.mockito.ArgumentMatchers.any;
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
 * 另有三个运行期补充：
 * <ul>
 *     <li>{@link RulePublishService} 虽是 {@code @Component} 但构造需要 {@code DataSource}
 *     与 {@code spring.application.name}，测试不连库且不走发布，按类型排除；</li>
 *     <li>{@link DatabusExecutor} 构造需要 {@link ISysDatabusConnectionService} 与
 *     {@link DatabusChainMapper}：前者实现类是 {@code @Service} 已被注解过滤，
 *     补一个 Mockito 桩（返回空连接列表）；后者是 MyBatis Mapper，本上下文不装配
 *     mybatis，同样补桩——未打桩的 {@code selectOne} 默认返回 null，而
 *     {@code execute} 对链路查不到不拦执行，本地 EL 链路照常跑；</li>
 *     <li>{@code alert} / {@code job} / {@code market} 三包按正则整体排除（2026-10-23）：
 *     告警评估触发器、通知器与三个定时 Job 的构造参数依赖 {@code @Service} 层
 *     （Alert/Metric/Execution Service，均已按注解过滤），冒烟上下文无数据库装配不了，
 *     且引擎冒烟链路不触发告警评估与定时聚合，排除即对齐「冒烟只测引擎」的本意。
 *     但引擎侧 {@link FlowExecutionTraceLifeCycle} 构造依赖 {@link AlertEvaluateTrigger}，
 *     手工补一个 Mockito 桩（触发告警评估为 no-op）。
 *     {@code market} 包同理：包里的 bean（浏览 {@code MarketQueryService}、安装
 *     {@code MarketInstallService}、上架 {@code MarketPublishService} 三个协作者类，
 *     加上 {@code MarketClosureLoader}、{@code MarketVoAssembler}）构造都要
 *     {@code IDatabusChainService} 或 Mapper（前者已按注解过滤，后者本上下文不装配），
 *     而冒烟链路不做市场上架/安装，排除即可，无需补桩；</li>
 *     <li>执行追踪 / 脚本补课链路的 MyBatis Mapper（{@link DatabusChainMapper}、
 *     {@link DatabusExecutionMapper}、{@link DatabusExecutionNodeMapper}、
 *     {@link DatabusComponentMapper}）逐个补桩：本上下文刻意不装配 mybatis。
 *     链路查不到不拦执行、insert 落库 no-op；组件台账桩回空列表——启动补课走
 *     「无库存脚本件，跳过启动注册」（组件工件在 DB 不在代码里，正确性由
 *     seed SQL 人工保证，测试不伪造工件记录，2026-10-23 拍板）；
 *     钩子自身全 catch，落库异常不外溢；</li>
 *     <li>ID 生成链路（{@code ExecutionTrace.start} → {@code IdGeneratorUtil} 静态
 *     {@code SpringUtils.getBean(IdentifierGenerator.class)}）：{@code SpringUtils}
 *     注册在 common-core 包不被本扫描覆盖，hutool 静态 context 为空会炸类初始化；
 *     手工注册 {@code SpringUtils}（Aware 回调注入静态 context）+ mybatis-plus
 *     默认雪花 {@code IdentifierGenerator} 两个 bean。</li>
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
            classes = RulePublishService.class),
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "org\\.dromara\\.databus\\.(alert|job|market)\\..*")
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

    /**
     * 链路 Mapper 测试桩：无数据库环境下 {@code DatabusExecutor.execute} 开头的
     * 链路登记表查询返回 null（查不到不拦执行），引擎只跑本地 EL 文件链路，不落库。
     */
    @Bean
    public DatabusChainMapper databusChainMapper() {
        return mock(DatabusChainMapper.class);
    }

    /**
     * 执行记录总账/节点明细 Mapper 测试桩：insert 为 no-op（Mockito 默认），
     * {@code ExecutionTraceRecorder} 的 REQUIRES_NEW 落库在无事务管理器上下文中
     * 抛错，由 {@code FlowExecutionTraceLifeCycle} 钩子全 catch 吞成日志，不影响测试。
     */
    @Bean
    public DatabusExecutionMapper databusExecutionMapper() {
        return mock(DatabusExecutionMapper.class);
    }

    /**
     * 节点明细 Mapper 测试桩：insertBatch 为 no-op，明细失败本就只 warn 不连坐总账。
     */
    @Bean
    public DatabusExecutionNodeMapper databusExecutionNodeMapper() {
        return mock(DatabusExecutionNodeMapper.class);
    }

    /**
     * 组件台账 Mapper 测试桩：返回空列表——启动补课 Runner 走「无库存脚本件，
     * 跳过启动注册」。组件的脚本工件在 DB 不在代码里（2026-10-06「18 件迁入
     * DB 脚本工件」），其正确性由 seed SQL 人工保证，测试不伪造工件记录、
     * 不保留依赖 DB 工件组件的用例（2026-10-23 拍板）。
     */
    @Bean
    public DatabusComponentMapper databusComponentMapper() {
        DatabusComponentMapper mapper = mock(DatabusComponentMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        return mapper;
    }

    /**
     * 告警评估触发器测试桩：alert 包已按正则排除（构造依赖 @Service 层），
     * 引擎侧 {@code FlowExecutionTraceLifeCycle} 仍构造依赖本类，补 mock 桩，
     * 总账落库后的事件驱动告警触发为 no-op。
     */
    @Bean
    public AlertEvaluateTrigger alertEvaluateTrigger() {
        return mock(AlertEvaluateTrigger.class);
    }

    /**
     * 手工注册 SpringUtils（@Component 在 common-core 包，不在本扫描范围）：
     * ApplicationContextAware 回调把上下文写入 hutool SpringUtil 静态持有器，
     * 供 {@code IdGeneratorUtil} 等静态工具按需 getBean。
     */
    @Bean
    public static SpringUtils springUtils() {
        return new SpringUtils();
    }

    /**
     * 雪花 ID 生成器（mybatis-plus 默认实现）：{@code ExecutionTrace.start} 挂牌时
     * {@code IdGeneratorUtil} 静态初始化 getBean 本类型，生产由 mybatis 自动装配提供。
     */
    @Bean
    public IdentifierGenerator identifierGenerator() {
        return DefaultIdentifierGenerator.getInstance();
    }

}
