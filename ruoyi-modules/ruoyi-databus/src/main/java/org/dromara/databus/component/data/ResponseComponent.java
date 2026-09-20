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
 * <p>
 * 同时镜像一份到节点 tag 命名空间（{@code $.<tag>.result/msg/data}），
 * 遵循"每个节点产出在自己 tag 下"的统一契约，供 {@link
 * org.dromara.databus.executor.NodeStepResultCollector} 按统一规则采集
 * 数据明细快照；{@code $.response.*} 仍是链路总响应出口，不受影响。
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
        String msgText = resolvedMsg == null ? null : resolvedMsg.toString();

        save("$.response.result", success);
        save("$.response.msg", msgText);

        Object data = null;
        if (cfg != null && cfg.getDataPath() != null && !cfg.getDataPath().isBlank()) {
            data = getOptional(cfg.getDataPath());
        }
        save("$.response.data", data);

        // 镜像到 tag 命名空间，供 collector 按统一契约采集节点产出快照
        String tag = this.getTag();
        if (tag != null && !tag.isBlank()) {
            String prefix = "$." + tag + ".";
            save(prefix + "result", success);
            save(prefix + "msg", msgText);
            save(prefix + "data", data);
        }
        // 成败表格列已有，只有业务消息 msg 是增量信息；无 msg 不写，前端兜底「完成」
        if (msgText != null && !msgText.isBlank()) {
            resultSummary(msgText);
        }
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
