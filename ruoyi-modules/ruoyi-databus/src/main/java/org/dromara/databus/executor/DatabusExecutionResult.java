package org.dromara.databus.executor;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 数据总线链路执行结果。
 * <p>
 * 对 LiteFlow {@code LiteflowResponse} 的二次封装，补充数据总线自身的 executionId、
 * 上下文快照与节点级执行步骤，供执行记录持久化与前端瀑布图展示使用。
 *
 * @author databus
 */
@Data
public class DatabusExecutionResult {

    /** 执行记录业务 id（DatabusExecutor 生成）。 */
    private String executionId;

    /** 链路编码。 */
    private String chainId;

    /** 执行是否成功。 */
    private boolean success;

    /** 失败时的错误信息。 */
    private String message;

    /** 节点执行步骤列表（按执行顺序）。 */
    private List<NodeStep> steps = new ArrayList<>();

    /** 执行结束后上下文的 JSON 快照。 */
    private String contextJson;

    /** 执行开始时间。 */
    private Date startTime;

    /** 执行结束时间。 */
    private Date endTime;

    /** 总耗时（毫秒）。 */
    private long costTime;

    /**
     * 单个节点的执行步骤信息。
     */
    @Data
    public static class NodeStep {

        /** 链路内节点标识。 */
        private String nodeId;

        /** 节点名称（如配置了 tag 则附带）。 */
        private String nodeName;

        /** 节点 tag。 */
        private String tag;

        /** 执行是否成功。 */
        private boolean success;

        /** 失败时的错误信息。 */
        private String errorMessage;

        /** 节点耗时（毫秒）。 */
        private Long timeSpent;

        /** 节点开始时间。 */
        private Date startTime;

        /** 节点结束时间。 */
        private Date endTime;
    }
}
