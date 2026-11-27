package org.dromara.databus.service;

import org.dromara.databus.agent.run.AgentChainSnapshot;
import org.dromara.databus.agent.run.AgentComponentDraftSnapshot;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * AI agent 改动快照 Service 接口（只增不改）：payload 用 resourceType 区分链路与组件草稿。
 *
 * @author databus
 */
public interface IDatabusAgentSnapshotService {

    /**
     * 动手前留一份链路快照（resourceType=CHAIN, operation=UPDATE），并同步 run.before_payload。
     *
     * @param runId  run 句柄
     * @param round  轮次（1 起）
     * @param before 动手前的链路（编辑前查出的整行）
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentChainSnapshot record(String runId, int round, DatabusChainVo before);

    /**
     * 记录一次新建（resourceType=CHAIN, operation=CREATE），只存定位信息，供回滚时删除整条链路。
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
     * 动手前留一份组件草稿快照（resourceType=COMPONENT_DRAFT, operation=UPDATE），存改前正文与元信息。
     * <p>
     * 不写 run.before_payload：该字段只表达链路「动手前结构」。
     *
     * @param runId  run 句柄
     * @param round  轮次
     * @param before 动手前的草稿行
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentComponentDraftSnapshot recordDraft(String runId, int round, DatabusComponentVo before);

    /**
     * 记录一次草稿新建（resourceType=COMPONENT_DRAFT, operation=CREATE），回滚语义是删掉该草稿行。
     *
     * @param runId         run 句柄
     * @param round         轮次
     * @param componentId   新建出来的草稿 id
     * @param componentCode 组件编码
     * @param componentName 组件名称
     * @return 落库的载荷（供调用方回执/日志）
     */
    AgentComponentDraftSnapshot recordDraftCreate(String runId, int round, Long componentId,
                                                  String componentCode, String componentName);

    /**
     * 取本 run 动过的每条链的最早一份快照（回退时按 run 由近及远写回链结构）。
     * <p>
     * 同一轮次内新建后再修改的链路，最早快照仍是 CREATE，回滚语义即删除整条链路。
     *
     * @param runId run 句柄
     * @return 每条链一份载荷，按首次出现的轮次升序
     */
    List<AgentChainSnapshot> listEarliestChainsByRun(String runId);

    /**
     * 取本 run 动过的每个组件草稿的最早一份快照（回退时删新建 / 写回改前内容）。
     *
     * @param runId run 句柄
     * @return 每个草稿一份载荷，按首次出现的轮次升序
     */
    List<AgentComponentDraftSnapshot> listEarliestDraftsByRun(String runId);

    /**
     * 批量判断哪些 run 真正改过东西（有快照即改过），用于历史回放时补齐标记。
     *
     * @param runIds run 句柄集合
     * @return 有快照的 runId 集合
     */
    Set<String> changedRunIds(Collection<String> runIds);

    /**
     * 判断单个 run 是否真正改过东西（链路或草稿），用于 run 收尾后决定是否提供回退入口。
     *
     * @param runId run 句柄
     * @return true 表示本轮有快照
     */
    boolean hasChanges(String runId);

}