package org.dromara.databus.service;

import org.dromara.databus.agent.AgentChainSnapshot;
import org.dromara.databus.domain.vo.AgentSnapshotVo;
import org.dromara.databus.domain.vo.DatabusChainVo;

import java.util.List;

/**
 * AI agent 链路快照 Service 接口（只增不改）。
 *
 * @author databus
 */
public interface IDatabusAgentSnapshotService {

    /**
     * 动手前留一份链路快照，并同步 run.before_payload。
     *
     * @param runId  run 句柄
     * @param round  轮次（1 起）
     * @param before 动手前的链路（编辑前查出的整行）
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentChainSnapshot record(String runId, int round, DatabusChainVo before);

    /**
     * 按 run 列出全部轮次快照（只回元信息，不含 payload）。
     *
     * @param runId run 句柄
     * @return 按 round 升序
     */
    List<AgentSnapshotVo> listByRunId(String runId);

    /**
     * 取指定轮次快照载荷。
     *
     * @param runId run 句柄
     * @param round 轮次
     * @return 载荷；不存在返回 null
     */
    AgentChainSnapshot find(String runId, Integer round);

}