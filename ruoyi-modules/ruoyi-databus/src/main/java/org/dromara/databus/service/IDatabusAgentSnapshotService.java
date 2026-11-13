package org.dromara.databus.service;

import org.dromara.databus.agent.AgentChainSnapshot;
import org.dromara.databus.domain.vo.DatabusChainVo;

import java.util.List;

/**
 * AI agent 链路快照 Service 接口（只增不改）。
 *
 * @author databus
 */
public interface IDatabusAgentSnapshotService {

    /**
     * 动手前留一份链路快照（operation=UPDATE），并同步 run.before_payload。
     *
     * @param runId  run 句柄
     * @param round  轮次（1 起）
     * @param before 动手前的链路（编辑前查出的整行）
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentChainSnapshot record(String runId, int round, DatabusChainVo before);

    /**
     * 记录一次新建（operation=CREATE），只存定位信息，供回滚时删除整条链路。
     * <p>
     * 不写 run.before_payload：新建没有「动手前」可写回的结构。
     *
     * @param runId     run 句柄
     * @param round     轮次（与修改共用 run.round 号源）
     * @param chainId   新建出来的链路 id
     * @param chainCode 链路编码
     * @param chainName 链路名称
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentChainSnapshot recordCreate(String runId, int round, Long chainId, String chainCode, String chainName);

    /**
     * 取本 run 动过的每条链的最早一份快照（回滚到「这次对话前」用）。
     * <p>
     * 同一轮次内新建后再修改的链路，最早快照仍是 CREATE，回滚语义即删除整条链路。
     *
     * @param runId run 句柄
     * @return 每条链一份载荷，按首次出现的轮次升序
     */
    List<AgentChainSnapshot> listEarliestByRun(String runId);

}