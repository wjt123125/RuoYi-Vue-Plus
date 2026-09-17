package org.dromara.databus.component.data;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.component.DatabusNodeComponent;

/**
 * 响应组装组件（注册名 {@code response}）。
 * <p>
 * 链路的标准出口：把执行结果统一写入上下文固定位置
 * {@code $.response.result}（布尔）、{@code $.response.msg}（字符串）、
 * {@code $.response.data}（dataPath 指向的数据，缺省为 null）。
 * 与其他组件不同，它不往自己的数据空间（tag）下写产出。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("response")
public class ResponseComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        ResponseCfg cfg = this.getCmpData(ResponseCfg.class);

        Object resolvedResult = resolveParam(cfg == null ? null : cfg.getResult());
        boolean success = toBoolean(resolvedResult);
        Object resolvedMsg = resolveParam(cfg == null ? null : cfg.getMsg());

        save("$.response.result", success);
        save("$.response.msg", resolvedMsg == null ? null : resolvedMsg.toString());

        Object data = null;
        if (cfg != null && cfg.getDataPath() != null && !cfg.getDataPath().isBlank()) {
            data = getOptional(cfg.getDataPath());
        }
        save("$.response.data", data);
        log.debug("[databus] response 组装完成 success={}, tag={}", success, this.getTag());
    }

    /**
     * 布尔宽容转换：真正的 Boolean 直接取；其他类型按字符串 "true" 判定；null 为 false。
     */
    private boolean toBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }
}
