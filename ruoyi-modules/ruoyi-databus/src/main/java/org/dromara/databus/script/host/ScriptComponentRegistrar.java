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
 * 脚本组件的「上岗登记处」：编译车间交来成品（{@link ScriptArtifact}），
 * 这里把人造出来、安排进 LiteFlow 的工位，顺带管一本运行台账。
 *
 * <p>造人用 Spring 的 createBean——走完整 Bean 生命周期（构造注入、@Autowired、
 * @PostConstruct、AOP 一个不少），但不把 bean 登记进容器：动态类名挤进单例池
 * 没好处，还挡着旧 {@link ScriptClassLoader} 卸载。安排工位用
 * {@code FlowBus.addManagedNode}——直接把造好的实例塞到 LiteFlow 手里。
 *
 * <p>为什么绕开 LiteFlow 自带的注册器（LiteflowNodeBuilder）：它的登记习惯是
 * 只记一个「类名字符串」，等人要用了再拿应用类加载器 Class.forName 去找。
 * 可我们的类只活在 ScriptClassLoader 的内存里，应用类加载器看不见这个名字。
 * 所以不走「报名字、回头查」的路子，直接把人领到工位坐下。addManagedNode
 * 底层是裸 Map.put：不查重，同 code 后来的盖掉先来的——热更、接管内置件
 * （同 code 的脚本版盖掉内置 @LiteflowComponent）都靠这一下；旧加载器就此
 * 失去可达引用，GC 找机会把整代旧类收走。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptComponentRegistrar {

    private final ApplicationContext applicationContext;

    /**
     * code → 当前生效工件。既是台账，也替当前 {@link ScriptClassLoader}
     * 兜着引用：put 覆盖的瞬间，旧版本在这条账上就没名额了，旧加载器只剩
     * FlowBus 里旧节点实例还短暂捏着——等它也被覆盖，整代旧类就能交给
     * GC 回收。
     */
    private final Map<String, LoadedScript> active = new ConcurrentHashMap<>();

    /**
     * 组件 id → 运行时健康台账。比 active 收得宽：启动期失败的件
     * 没注册上工（不进 active），但病志也得入账——不然「库里明明有件，
     * 工位上怎么没人」没处对证。
     */
    private final Map<Long, ScriptRuntimeVo> health = new ConcurrentHashMap<>();

    /**
     * 注册（或热替换）一个脚本组件：造实例 → 认节点类型 → 塞进 FlowBus →
     * 记两本账（active + health）。
     *
     * <p>实例化/注入失败、FlowBus 注册失败都直接抛异常，由保存管线回滚事务——
     * 这是「能保存的就能跑」的后半段：数据库不留编不过的版本，FlowBus 也不
     * 停在半成品状态，现网永远跑最近一次注册成功的版本。
     */
    public void register(DatabusComponent row, ScriptArtifact artifact) {
        String code = row.getComponentCode();
        Class<?> primaryClass = artifact.primaryClass();
        NodeComponent instance;
        try {
            // AUTOWIRE_CONSTRUCTOR：构造注入 + 字段注入 + initializeBean（@PostConstruct/AOP）全流程都走，
            // 但不把 bean 登记进容器——动态类名挤进单例池没好处，还挡着旧 ClassLoader 卸载
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
            // addManagedNode：guessType + initComponent + put2NodeMap（裸 put，不查重），
            // 同 code 后到者盖掉先到者——热更和接管内置件都靠这一下
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
     * 记一笔「上岗失败」：启动补课时有件编不过或造不出来，不炸整个启动，
     * 只把病志写进台账（不健康 + 失败原因 + 时间）。台账接口上看得见，
     * 其余组件照常上工——一个坏脚本不拖死整个应用。
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
     * 组件删除时清账（active 摘名额、health 撤病志）。刻意不主动从 FlowBus
     * 摘节点：删除动作前面有「链路引用拦截」挡着（被编排引用的件删不掉），
     * 真能删掉的件即使 FlowBus 里残留一个节点副本，也没有链路会走到它，
     * 重启后自然消失。
     */
    public void forget(String code) {
        LoadedScript loaded = active.remove(code);
        if (loaded != null) {
            health.remove(loaded.componentId());
        }
    }

    /**
     * 健康快照：把台账抄一份给人看（逐条拷贝 VO，内部对象不外递，
     * 免得调用方改坏了账本）。失败件排前面、其余按组件 id 升序——
     * 看快照的人多半是来查病的，病号优先。
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

    /**
     * 单条拷贝：new 一个 VO 逐字段抄一遍，把台账原件和快照隔开，
     * 调用方改的是抄本，坏不了账。
     */
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

    /**
     * 把层层包装的异常剥到最根上：Spring/createBean 出的错裹好几层皮，
     * 根因那一句才值得写进给用户看的报错。
     */
    private String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() != null ? cur.getMessage() : e.getClass().getSimpleName();
    }

    /**
     * 一个在工位上的脚本件：随身带着加载器（引用在手，GC 卸载就轮不到它）、
     * 工件本体、台账 id 和版本号。
     */
    private record LoadedScript(ScriptClassLoader classLoader,
                                ScriptArtifact artifact,
                                Long componentId,
                                int version) {
    }
}
