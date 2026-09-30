package org.dromara.databus.executor.trace;

import lombok.Getter;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.dromara.databus.enums.LogLevelEnum;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 执行追踪牌：一次正式执行一块，由 {@code DatabusExecutor.execute()} 在开跑前挂上
 * DatabusContext，流程结束的 afterFlow 钩子里凭它落库（设计档 §4.3）。
 * <p>
 * 挂牌规则：
 * <ul>
 *     <li>按 chainCode 查到链路：读其 log_level，OFF 不挂牌（完全不落库）；</li>
 *     <li>链路查不到：挂 BASIC 牌（chainId 为空），照常执行让 LiteFlow 暴露 ChainNotFound，
 *     同时留一条失败总账；</li>
 *     <li>试运行（executeByEl）永远不挂牌。</li>
 * </ul>
 * <p>
 * 并发红线：WHEN 并行时节点 after 钩子在 CompletableFuture 工作线程并发触发，
 * {@link #nodeRows} 必须是线程安全队列（ArrayList 会丢数据）；allOf 的 join
 * 构成 happens-before，afterFlow 排空时数据必齐。
 *
 * @author databus
 */
@Getter
public class ExecutionTrace {

    /**
     * 执行记录id（databus_execution 主键，节点行外键；挂牌时用雪花算法预生成）
     */
    private final Long recordId;

    /**
     * 链路id（链路查不到时为 null）
     */
    private final Long chainId;

    /**
     * 链路编码
     */
    private final String chainCode;

    /**
     * 记录档位（BASIC/FULL；OFF 不会挂牌）
     */
    private final String logLevel;

    /**
     * 执行入参 JSON（开跑前数据树快照，重跑据此还原）
     */
    private final String requestData;

    /**
     * 执行开始时间（execute2Resp 之前，端到端口径）
     */
    private final Date startTime;

    /**
     * FULL 档节点行缓冲（WHEN 并发追加，afterFlow 单次排空）
     */
    private final Queue<NodeTraceRow> nodeRows = new ConcurrentLinkedQueue<>();

    /**
     * 节点执行前整树快照：ThreadLocal 线程隔离（WHEN 并行线程各自一份），
     * before 钩子拍、after 钩子消费即删。ThreadLocal 挂在追踪牌实例上，
     * 一次执行一块牌，执行结束随牌一起回收。
     */
    private final ThreadLocal<String> inputSnapshot = new ThreadLocal<>();

    private ExecutionTrace(Long recordId, Long chainId, String chainCode, String logLevel,
                           String requestData, Date startTime) {
        this.recordId = recordId;
        this.chainId = chainId;
        this.chainCode = chainCode;
        this.logLevel = logLevel;
        this.requestData = requestData;
        this.startTime = startTime;
    }

    /**
     * 开一块追踪牌（id 在挂牌时生成，保证总账主键与节点行外键执行前就已确定）。
     */
    public static ExecutionTrace start(Long chainId, String chainCode, String logLevel,
                                       String requestData, Date startTime) {
        return new ExecutionTrace(IdGeneratorUtil.nextLongId(), chainId, chainCode, logLevel,
            requestData, startTime);
    }

    /**
     * 是否 FULL 档（只有 FULL 采集节点明细与节点前快照）。
     */
    public boolean isFull() {
        return LogLevelEnum.FULL.getCode().equals(logLevel);
    }

    /**
     * before 钩子拍摄节点执行前数据树整树快照（仅 FULL 档由采集器调用）。
     */
    public void captureInput(String json) {
        inputSnapshot.set(json);
    }

    /**
     * after 钩子取走本线程的节点前快照并清空，未拍到返回 null。
     */
    public String consumeInput() {
        String value = inputSnapshot.get();
        inputSnapshot.remove();
        return value;
    }

    /**
     * 追加一行节点轨迹（多线程并发安全）。
     */
    public void appendNode(NodeTraceRow row) {
        nodeRows.add(row);
    }

    /**
     * 排空全部节点行（afterFlow 落库时调用一次；并发追加在此前已由 join 保证可见）。
     */
    public List<NodeTraceRow> drainNodes() {
        List<NodeTraceRow> rows = new ArrayList<>();
        NodeTraceRow row;
        while ((row = nodeRows.poll()) != null) {
            rows.add(row);
        }
        return rows;
    }

}
