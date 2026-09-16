package org.dromara.databus.component;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据总线 LiteFlow 冒烟测试上下文。
 * <p>
 * 仅用于阶段 0 验证组件注册、顺序执行和异常捕获，阶段 1 会被正式
 * {@code DatabusContext}（含 JSONPath 读写、节点入出参快照等能力）替换。
 *
 * @author databus
 */
@Data
public class DatabusSmokeContext {

    /**
     * 组件执行轨迹，按顺序记录命中的组件标识，用于断言链路真实跑过哪些节点。
     */
    private final List<String> trace = new ArrayList<>();

    /**
     * 记录一个执行步骤。
     *
     * @param step 组件标识
     */
    public void record(String step) {
        trace.add(step);
    }

}
