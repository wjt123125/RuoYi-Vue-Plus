package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;

import java.util.List;

/**
 * 异常识别组件（exceptionInspect）的节点参数。
 * <p>
 * 必须放在 CATCH 的异常处理体（DO）内：读取 slot 上被捕获的异常，按 {@link #routes}
 * 配置顺序做 instanceof 匹配（支持子类；{@link #matchCause} 开启时沿 cause 链追查），
 * 把命中的路由名/异常类名/消息写入本节点 tag 命名空间，供后续 switchRoute 等值分流。
 * <pre>
 * {
 *   "routes": [
 *     { "type": "ARGUMENT", "className": "java.lang.IllegalArgumentException" },
 *     { "type": "NPE",      "className": "java.lang.NullPointerException" }
 *   ],
 *   "matchCause": true,
 *   "defaultType": "OTHER"
 * }
 * </pre>
 *
 * @author databus
 */
@Data
public class ExceptionInspectCfg {

    /**
     * 异常类型对照表：按数组顺序逐条 instanceof，命中第一条即输出其 type。
     * 顺序即优先级——父类（如 java.lang.Exception）若排在前面会抢先命中其子类。
     */
    @DatabusProp(label = "异常类型对照", required = true, order = 1,
        description = "按顺序匹配，命中第一条即输出路由名；父类要放在子类后面，否则抢先命中")
    private List<Route> routes;

    /**
     * 是否沿异常 cause 链追查（默认 true）：业务异常常被包装层包住，
     * 关闭后只识别最外层异常。
     */
    @DatabusProp(label = "追查 cause 链", order = 2,
        description = "开启后最外层未命中时继续逐层匹配 getCause()，适合识别被包装的根因异常")
    private Boolean matchCause;

    /**
     * 全部未命中时输出的路由名，缺省 OTHER。
     */
    @DatabusProp(label = "兜底路由名", order = 3, placeholder = "OTHER")
    private String defaultType;

    /**
     * 一条异常类型对照：type=命中后输出的路由名（交 switchRoute 分流），
     * className=异常全限定类名（instanceof 判定，子类可命中）。
     */
    @Data
    public static class Route {

        /**
         * 路由名：写入 {@code $.<tag>.type}，需与下游 switchRoute 的分支名一致。
         */
        @DatabusProp(label = "路由名", required = true, placeholder = "ARGUMENT")
        private String type;

        /**
         * 异常全限定类名。
         */
        @DatabusProp(label = "异常类名", required = true, placeholder = "java.lang.IllegalArgumentException")
        private String className;
    }
}
