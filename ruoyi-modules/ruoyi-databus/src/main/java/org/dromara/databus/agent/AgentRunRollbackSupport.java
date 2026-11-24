package org.dromara.databus.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.dromara.databus.constant.AgentConstants.OPERATION_CREATE;
import static org.dromara.databus.constant.CommonConstants.OWNER_SYSTEM;
import static org.dromara.databus.constant.CommonConstants.STATUS_DRAFT;

/**
 * run 级回滚与轮次删除：按快照撤销链路改动、逻辑删除 run、由近及远回退历史轮次。
 * <p>
 * 只做「撤销既有 run」，不负责创建新一轮对话（重试仍在 Service 里编排）。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRollbackSupport {

    private final DatabusAgentRunMapper runMapper;
    private final IDatabusAgentSnapshotService snapshotService;
    private final IDatabusChainService chainService;
    private final AgentRunReplayAssembler replayAssembler;

    /**
     * 回到这次对话前：按 run 整体回滚，对本 run 动过的每条链取本 run 内最早一份快照，
     * UPDATE 写回原结构、CREATE 删除整条链路。
     *
     * @param runId run 句柄
     * @return 被回滚的链路（deleted=true 表示整条已删除）
     */
    public List<AgentRollbackVo> rollbackRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        if (AgentRunStatusEnum.RUNNING.getCode().equals(run.getStatus())) {
            throw new ServiceException("任务还在进行中，结束后才能回滚");
        }
        List<AgentChainSnapshot> earliest = snapshotService.listEarliestByRun(runId);
        if (earliest.isEmpty()) {
            throw new ServiceException("这次对话没有可回滚的操作");
        }
        List<AgentRollbackVo> result = new ArrayList<>(earliest.size());
        for (AgentChainSnapshot snapshot : earliest) {
            boolean created = OPERATION_CREATE.equals(snapshot.getOperation());
            if (created) {
                deleteCreatedChain(run, snapshot.getChainId());
            } else {
                restoreChain(run, snapshot);
            }
            result.add(new AgentRollbackVo(snapshot.getChainId(), created));
        }
        return result;
    }

    /**
     * 删除一轮对话：逻辑删除该 run，历史回放不再出现这一轮。
     *
     * @param runId run 句柄
     */
    public void deleteRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        ensureSettled(run, "删除");
        // 逻辑删除即可：step / message 保留，但历史回放与上下文续接都不再看到这一轮
        runMapper.deleteById(run.getId());
    }

    /**
     * 回退到本轮对话发起前：由近及远撤销本轮及之后各轮的链路改动，
     * 并删除这些轮次的历史，刷新后不再回放。
     *
     * @param runId run 句柄
     * @return 被回滚的链路（deleted=true 表示整条已删除）
     */
    public List<AgentRollbackVo> revertRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        ensureSettled(run, "回退");
        List<DatabusAgentRun> runs = listConversationRuns(run.getUserId(), run.getConversationId());
        int index = -1;
        for (int i = 0; i < runs.size(); i++) {
            if (runId.equals(runs.get(i).getRunId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new ServiceException("任务不存在");
        }
        List<DatabusAgentRun> reverted = runs.subList(index, runs.size());
        for (DatabusAgentRun item : reverted) {
            if (AgentRunStatusEnum.RUNNING.getCode().equals(item.getStatus())) {
                throw new ServiceException("还有任务在进行中，结束后才能回退");
            }
        }
        // 由近及远逐个撤销：最后写回的这份就是「本轮发起前」的结构
        Map<Long, AgentRollbackVo> rolled = new LinkedHashMap<>();
        for (int i = reverted.size() - 1; i >= 0; i--) {
            DatabusAgentRun item = reverted.get(i);
            if (snapshotService.listEarliestByRun(item.getRunId()).isEmpty()) {
                continue;
            }
            for (AgentRollbackVo vo : rollbackRun(item.getRunId())) {
                rolled.put(vo.getChainId(), vo);
            }
        }
        // 历史一起截断：刷新后不再回放出这一轮及之后的对话
        runMapper.deleteBatchIds(reverted.stream().map(DatabusAgentRun::getId).toList());
        return new ArrayList<>(rolled.values());
    }

    /**
     * 会话内全部有效 run，按时间正序（同刻按自增 id），重试与回退都靠这个顺序定位轮次。
     *
     * @param userId         归属用户
     * @param conversationId 会话 id
     * @return 按发生顺序排列的 run
     */
    public List<DatabusAgentRun> listConversationRuns(Long userId, String conversationId) {
        return runMapper.selectList(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .orderByAsc(DatabusAgentRun::getCreateTime)
            .orderByAsc(DatabusAgentRun::getId));
    }

    /**
     * 未结束的 run 不允许做删除 / 重试 / 回退。
     *
     * @param run    任务
     * @param action 动作名（用于提示）
     */
    public void ensureSettled(DatabusAgentRun run, String action) {
        if (AgentRunStatusEnum.RUNNING.getCode().equals(run.getStatus())) {
            throw new ServiceException("任务还在进行中，结束后才能" + action);
        }
    }

    /**
     * 把快照里的链路结构整条写回（回滚的 UPDATE 分支）。
     *
     * @param run      当前 run
     * @param snapshot 本 run 内该链路最早一份快照
     */
    private void restoreChain(DatabusAgentRun run, AgentChainSnapshot snapshot) {
        DatabusChainBo bo = new DatabusChainBo();
        bo.setId(snapshot.getChainId());
        bo.setChainCode(snapshot.getChainCode());
        bo.setChainName(snapshot.getChainName());
        bo.setCmpProperty(snapshot.getCmpProperty());
        bo.setCanvasData(snapshot.getCanvasData());
        bo.setInputParams(snapshot.getInputParams());
        bo.setLogLevel(snapshot.getLogLevel());
        bo.setUpdateBy(run.getUserId());
        if (!Boolean.TRUE.equals(chainService.updateByBo(bo, run.getUserId()))) {
            throw new ServiceException("回滚失败");
        }
    }

    /**
     * 撤销一次新建：仅当链路仍是「本人创建 + 草稿态 + 非系统链」时整条删除。
     * <p>
     * 链路一旦被别人改过、已发布或被其它链引用，就不该再当「AI 刚建的」删掉——
     * 此时抛错让用户放弃撤销，而不是覆盖或强删。
     *
     * @param run     当前 run
     * @param chainId 新建出来的链路 id
     */
    private void deleteCreatedChain(DatabusAgentRun run, Long chainId) {
        DatabusChainVo chain = chainService.queryById(chainId);
        if (chain == null) {
            throw new ServiceException("该链路已不存在，无法撤销新建");
        }
        if (OWNER_SYSTEM.equals(chain.getOwnerType())
            || !STATUS_DRAFT.equals(chain.getStatus())
            || !Objects.equals(chain.getCreateBy(), run.getUserId())) {
            throw new ServiceException("该链路已被修改或非草稿，无法撤销新建");
        }
        boolean deleted;
        try {
            deleted = Boolean.TRUE.equals(chainService.deleteByIds(List.of(chainId)));
        } catch (Exception e) {
            log.warn("[databus-agent] 撤销新建失败 runId={} chainId={}：{}", run.getRunId(), chainId, e.getMessage());
            throw new ServiceException("该链路已被引用，无法撤销新建");
        }
        if (!deleted) {
            throw new ServiceException("该链路已被修改或非草稿，无法撤销新建");
        }
    }
}
