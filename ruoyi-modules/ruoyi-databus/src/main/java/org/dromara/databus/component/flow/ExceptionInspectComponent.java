package org.dromara.databus.component.flow;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.slot.Slot;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.component.cfg.ExceptionInspectCfg;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.springframework.util.ClassUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 异常识别组件（注册名 {@code exceptionInspect}）。
 * <p>
 * CATCH 异常处理体（DO）内的「验票员」：LiteFlow 的 CATCH 只兜底不分类、switchRoute
 * 只会等值比较不会 instanceof，本组件补上「认出异常类型」这一步——从 slot 读取被捕获的
 * 异常，按配置顺序做类型匹配（有序 + 子类语义 + 可选 cause 链追查），把结果写入本节点
 * tag 命名空间，再交 switchRoute 按 {@code $.<tag>.type} 等值分流。
 * <p>
 * 输出（统一 tag 命名空间契约）：
 * <ul>
 *   <li>{@code $.<tag>.type}：命中的路由名，全部未命中取 defaultType（缺省 OTHER）；</li>
 *   <li>{@code $.<tag>.className}：实际捕获异常的全限定类名；</li>
 *   <li>{@code $.<tag>.message}：异常消息（null 落空串，避免下游拼出字面量 null）。</li>
 * </ul>
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("exceptionInspect")
@DatabusCmp(
    code = "exceptionInspect",
    name = "异常识别",
    icon = "ph:bug",
    color = "#e6a23c",
    group = "other",
    sort = 60,
    description = "CATCH 异常处理体内使用：按对照表顺序 instanceof 识别异常类型（支持子类、可追查 cause），输出 $.<tag>.type/className/message，再接 SWITCH 分流",
    cfg = ExceptionInspectCfg.class,
    dataExample = """
        {"routes":[{"type":"ARGUMENT","className":"java.lang.IllegalArgumentException"},{"type":"NPE","className":"java.lang.NullPointerException"}],"matchCause":true,"defaultType":"OTHER"}
        """
)
public class ExceptionInspectComponent extends DatabusNodeComponent {

    /**
     * 异常类元数据缓存：类名 → Class，避免每条链每次执行都 Class.forName。
     * 类不存在时不放缓存（每次报错），引导用户修配置。
     */
    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    @Override
    public void process() {
        ExceptionInspectCfg cfg = this.getCmpData(ExceptionInspectCfg.class);
        List<ExceptionInspectCfg.Route> routes = cfg == null ? null : cfg.getRoutes();
        if (routes == null || routes.isEmpty()) {
            throw new ServiceException("异常识别组件缺少 routes 配置（tag=" + this.getTag() + "）");
        }

        Slot slot = this.getSlot();
        Exception caught = slot.getException();
        if (caught == null) {
            // CATCH 的 DO 执行期间异常一定挂在 slot；走到这里说明组件被误放在 try 主体或普通链路
            throw new ServiceException("异常识别组件必须放在 CATCH 的异常处理体（DO）内，当前没有被捕获的异常（tag="
                + this.getTag() + "）");
        }

        String defaultType = (cfg.getDefaultType() == null || cfg.getDefaultType().isBlank())
            ? "OTHER" : cfg.getDefaultType().trim();
        boolean matchCause = cfg.getMatchCause() == null || cfg.getMatchCause();

        String matchedType = null;
        // 外层异常优先：每层 cause 上按 routes 顺序匹配；route 顺序即优先级（父类排子类之后）
        Throwable cursor = caught;
        while (cursor != null) {
            for (ExceptionInspectCfg.Route route : routes) {
                if (route == null || route.getType() == null || route.getType().isBlank()
                    || route.getClassName() == null || route.getClassName().isBlank()) {
                    throw new ServiceException("异常识别组件存在路由名或异常类名为空的对照条目（tag="
                        + this.getTag() + "）");
                }
                Class<?> routeClass = resolveClass(route.getClassName().trim());
                if (routeClass.isInstance(cursor)) {
                    matchedType = route.getType().trim();
                    break;
                }
            }
            if (matchedType != null) {
                break;
            }
            cursor = matchCause ? cursor.getCause() : null;
        }

        String type = matchedType != null ? matchedType : defaultType;
        String tag = this.getTag();
        save("$." + tag + ".type", type);
        save("$." + tag + ".className", caught.getClass().getName());
        save("$." + tag + ".message", caught.getMessage() == null ? "" : caught.getMessage());

        resultSummary(matchedType != null ? "识别为 " + type : "未命中类型，归 " + type);
        log.info("[databus] exceptionInspect tag={}, exception={}, route={}",
            tag, caught.getClass().getName(), type);
    }

    /**
     * 按全限定类名加载 Class（带缓存）；类不存在抛带修复指引的配置错误。
     */
    private Class<?> resolveClass(String className) {
        Class<?> cached = CLASS_CACHE.get(className);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> clazz = ClassUtils.forName(className, ClassUtils.getDefaultClassLoader());
            CLASS_CACHE.putIfAbsent(className, clazz);
            return clazz;
        } catch (ClassNotFoundException e) {
            throw new ServiceException("异常识别组件配置的异常类不存在：" + className
                + "（请检查全限定类名，tag=" + this.getTag() + "）");
        }
    }
}
