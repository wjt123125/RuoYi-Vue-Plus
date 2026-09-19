package org.dromara.databus.executor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 挂到 LiteFlow {@code CmpStep.stepData} 上的本步观测载荷。
 * <p>
 * 由 {@link NodeStepResultCollector} 在每个节点执行结束的生命周期钩子里当场采集，
 * 执行完成后 {@link DatabusExecutor} 从步骤队列取出并转成 VO 字段。
 *
 * @author databus
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StepResultPayload {

    /**
     * 人话执行结果（组件在 process 收尾时自报，未报为 null，前端兜底显示「完成」）。
     */
    private String summary;

    /**
     * 该步数据空间 {@code $.<tag>} 子树的当场 JSON 快照（钩子里即时序列化，循环各轮各保现场）。
     */
    private String detailJson;
}
