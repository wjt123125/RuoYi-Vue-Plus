package org.dromara.databus.script.host;

import com.yomahub.liteflow.core.NodeComponent;
import com.yomahub.liteflow.enums.NodeTypeEnum;
import com.yomahub.liteflow.flow.FlowBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 脚本组件运行时注册器：把编译产物实例化（走完整 Spring Bean 生命周期，
 * 支持 @Autowired/构造注入/@PostConstruct）并挂入 LiteFlow FlowBus。
 *
 * <p>注册路径刻意绕过 {@code LiteFlowNodeBuilder.setClazz(Class)}——它只存类名字符串，
 * build 时 {@code Class.forName} 走应用类加载器，看不到自定义 {@link ScriptClassLoader}
 * 内的类；这里直接 {@code createBean} 出实例后调 {@code FlowBus.addManagedNode}，
 * 其底层是裸 Map.put（不查重，后者覆盖前者），同 code 热更即时生效，
 * 旧 ClassLoader 在替换后无可达路径由 GC 卸载。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptComponentRegistrar {

    private final ApplicationContext applicationContext;

    /**
     * code → 当前生效工件（持有当前 ClassLoader；替换即释放旧加载器引用）
     */
    private final Map<String, LoadedScript> active = new ConcurrentHashMap<>();

    /**
     * 组件 id → 运行时健康（含启动期失败件，失败件不在 active 中）
     */
    private final Map<Long, ScriptRuntimeVo> health = new ConcurrentHashMap<>();

    /**
     * 注册（或热替换）一个脚本组件。
     * <p>实例化/注入失败与 FlowBus 注册失败均抛出异常，由保存管线回滚事务——
     * 保证“能保存即可运行”，FlowBus 不会停留在半成品状态。
     */
    public void register(DatabusComponent row, ScriptArtifact artifact) {
        String code = row.getComponentCode();
        Class<?> primaryClass = artifact.primaryClass();
        NodeComponent instance;
        try {
            // AUTOWIRE_CONSTRUCTOR：构造注入 + 字段注入 + initializeBean（@PostConstruct/AOP）全流程，
            // 但不把 bean 注册进容器（避免动态类名污染单例池，也便于旧 ClassLoader 卸载）
            Object bean = applicationContext.getAutowireCapableBeanFactory()
                .createBean(primaryClass, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
            if (!(bean instanceof NodeComponent nodeComponent)) {
                throw new ServiceException("脚本主类不是 NodeComponent 实例：" + primaryClass.getName());
            }
            instance = nodeComponent;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("脚本组件实例化/依赖注入失败（" + primaryClass.getName()
                + "）：" + rootMessage(e), e);
        }

        NodeTypeEnum nodeType = NodeTypeEnum.guessType(primaryClass);
        if (nodeType == null) {
            throw new ServiceException("无法识别脚本组件的 LiteFlow 节点类型（主类须继承 Node/Boolean/For/Iterator/Switch Component）："
                + primaryClass.getName());
        }
        try {
            // addManagedNode：guessType + initComponent + put2NodeMap（裸 put，同 code 覆盖内置件/旧版本）
            FlowBus.addManagedNode(code, instance);
        } catch (Exception e) {
            throw new ServiceException("FlowBus 注册失败（code=" + code + "）：" + rootMessage(e), e);
        }

        active.put(code, new LoadedScript(artifact.classLoader(), artifact, row.getId(),
            row.getVersion() == null ? 0 : row.getVersion()));

        ScriptRuntimeVo vo = new ScriptRuntimeVo();
        vo.setComponentId(row.getId());
        vo.setComponentCode(code);
        vo.setVersion(row.getVersion());
        vo.setHealthy(Boolean.TRUE);
        vo.setRegisteredAt(LocalDateTime.now());
        health.put(row.getId(), vo);
        log.info("[databus-script] 脚本组件已注册 code={}, version={}, class={}",
            code, row.getVersion(), primaryClass.getName());
    }

    /**
     * 记录启动期注册失败（容错路径专用，不影响其他组件注册）。
     */
    public void markFailed(Long componentId, String code, Integer version, String error) {
        ScriptRuntimeVo vo = new ScriptRuntimeVo();
        vo.setComponentId(componentId);
        vo.setComponentCode(code);
        vo.setVersion(version);
        vo.setHealthy(Boolean.FALSE);
        vo.setError(error);
        vo.setFailedAt(LocalDateTime.now());
        health.put(componentId, vo);
    }

    /**
     * 组件删除时清理引用（不主动从 FlowBus 摘除：删除已被链路引用拦截，
     * 未被引用的残留节点无执行入口，重启后自然消失）。
     */
    public void forget(String code) {
        LoadedScript loaded = active.remove(code);
        if (loaded != null) {
            health.remove(loaded.componentId());
        }
    }

    /**
     * 运行时健康快照（按组件 id 升序）。
     */
    public List<ScriptRuntimeVo> snapshot() {
        return health.values().stream()
            .sorted((a, b) -> {
                if (a.getHealthy().equals(b.getHealthy())) {
                    return Long.compare(a.getComponentId(), b.getComponentId());
                }
                // 失败件排前
                return Boolean.FALSE.equals(a.getHealthy()) ? -1 : 1;
            })
            .map(this::copy)
            .toList();
    }

    private ScriptRuntimeVo copy(ScriptRuntimeVo source) {
        ScriptRuntimeVo vo = new ScriptRuntimeVo();
        vo.setComponentId(source.getComponentId());
        vo.setComponentCode(source.getComponentCode());
        vo.setVersion(source.getVersion());
        vo.setHealthy(source.getHealthy());
        vo.setError(source.getError());
        vo.setRegisteredAt(source.getRegisteredAt());
        vo.setFailedAt(source.getFailedAt());
        return vo;
    }

    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : e.getClass().getSimpleName();
    }

    private record LoadedScript(ScriptClassLoader classLoader,
                                ScriptArtifact artifact,
                                Long componentId,
                                int version) {
    }
}
