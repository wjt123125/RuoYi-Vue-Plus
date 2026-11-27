package org.dromara.databus.agent.run;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptDraftBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.AgentRevertPreviewVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dromara.databus.constant.AgentConstants.OPERATION_CREATE;
import static org.dromara.databus.constant.AgentConstants.REVERT_ACTION_DELETE;
import static org.dromara.databus.constant.AgentConstants.REVERT_ACTION_REFERENCE;
import static org.dromara.databus.constant.AgentConstants.REVERT_ACTION_RESTORE;
import static org.dromara.databus.constant.AgentConstants.REVERT_ACTION_SKIP;
import static org.dromara.databus.constant.AgentConstants.SNAPSHOT_RESOURCE_CHAIN;
import static org.dromara.databus.constant.AgentConstants.SNAPSHOT_RESOURCE_COMPONENT_DRAFT;
import static org.dromara.databus.constant.CommonConstants.OWNER_SYSTEM;

/**
 * run 级回滚与轮次删除：按快照撤销链路与组件改动、逻辑删除 run、由近及远回退历史轮次。
 * <p>
 * 回退分两步：{@link #previewRun(String)} 只读预检，列出「将撤销 / 将跳过 / 受影响」；
 * {@link #revertRun(String)} 按同一口径执行。撤回是默认能力：新建的链路无论草稿还是已发布都整条删除，
 * 新建的组件无论草稿还是已采纳都撤回（被引用的转软退役，存量链路照常运行）。
 * 只有两类确实退不掉的才跳过：资源已归属系统/他人，以及新建链路被回退范围外的链路引用。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunRollbackSupport {

    /** deleteCreatedChain 的哨兵返回值：当前还压着引用方，交给外层多轮重试 */
    private static final String BLOCKED_BY_REFERENCE = "__REFERENCED__";

    private final DatabusAgentRunMapper runMapper;
    private final IDatabusAgentSnapshotService snapshotService;
    private final IDatabusChainService chainService;
    private final IDatabusComponentService componentService;
    private final DatabusChainMapper chainMapper;
    private final AgentRunReplayAssembler replayAssembler;

    /**
     * 按单个 run 回滚：对本 run 动过的每条链/每个组件取本 run 内最早一份快照，
     * CREATE 整条撤回、UPDATE 写回原内容（重试复用本方法）。
     *
     * @param runId run 句柄
     * @return 每个资源一条结果（action=SKIP 表示未撤销，reason 说明原因）
     */
    public List<AgentRollbackVo> rollbackRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        ensureSettled(run, "回滚");
        Map<String, SnapshotRef> refs = collectEarliest(List.of(run));
        if (refs.isEmpty()) {
            throw new ServiceException("这次对话没有可回滚的操作");
        }
        return executeRefs(refs);
    }

    /**
     * 回退预检（只读）：算出「将撤销 / 将跳过 / 受影响」，供前端先给影响清单再让用户确认。
     *
     * @param runId run 句柄
     * @return 预检结果，三份清单都为空表示这一轮没有可回退的改动
     */
    public AgentRevertPreviewVo previewRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        ensureSettled(run, "回退");
        Map<String, SnapshotRef> refs = collectEarliest(resolveRevertedRuns(run));
        Set<Long> revertedChainIds = refs.values().stream()
            .filter(ref -> ref.chain() != null)
            .map(ref -> ref.chain().getChainId())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        AgentRevertPreviewVo preview = new AgentRevertPreviewVo();
        Map<Long, AgentRollbackVo> affected = new LinkedHashMap<>();
        for (SnapshotRef ref : refs.values()) {
            if (ref.chain() != null) {
                classifyChain(preview, affected, ref, revertedChainIds);
            } else {
                classifyDraft(preview, affected, ref, revertedChainIds);
            }
        }
        preview.setAffected(new ArrayList<>(affected.values()));
        return preview;
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
     * 回退到本轮对话发起前：撤销本轮及之后各轮的链路与组件改动，并删除这些轮次的历史。
     * <p>
     * 默认全部撤回，只有资源已归属系统/他人、或新建链路被回退范围外的链路引用时才跳过。
     *
     * @param runId run 句柄
     * @return 每个资源一条结果（含跳过项）
     */
    public List<AgentRollbackVo> revertRun(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        ensureSettled(run, "回退");
        List<DatabusAgentRun> reverted = resolveRevertedRuns(run);
        // 同一资源被多轮动过时取最早一份快照，统一执行一次：避免「后轮先删、前轮再写回」的互相踩踏
        List<AgentRollbackVo> rolled = executeRefs(collectEarliest(reverted));
        // 历史一起截断：刷新后不再回放出这一轮及之后的对话
        runMapper.deleteBatchIds(reverted.stream().map(DatabusAgentRun::getId).toList());
        return rolled;
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
     * 定位「本轮及之后」的 run，并拒绝回退含进行中任务的区间。
     *
     * @param run 起始轮
     * @return 从起始轮到最后一条 run（时间正序）
     */
    private List<DatabusAgentRun> resolveRevertedRuns(DatabusAgentRun run) {
        List<DatabusAgentRun> runs = listConversationRuns(run.getUserId(), run.getConversationId());
        int index = -1;
        for (int i = 0; i < runs.size(); i++) {
            if (run.getRunId().equals(runs.get(i).getRunId())) {
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
        return reverted;
    }

    /**
     * 合并各轮的最早快照：由近及远遍历，旧轮次覆盖新轮次。
     *
     * @param reverted 「本轮及之后」的 run（时间正序）
     * @return key = 资源类型:id，value = 该资源 + 持有最早快照的 run
     */
    private Map<String, SnapshotRef> collectEarliest(List<DatabusAgentRun> reverted) {
        Map<String, SnapshotRef> refs = new LinkedHashMap<>();
        for (int i = reverted.size() - 1; i >= 0; i--) {
            DatabusAgentRun item = reverted.get(i);
            for (AgentChainSnapshot chain : snapshotService.listEarliestChainsByRun(item.getRunId())) {
                refs.put(SNAPSHOT_RESOURCE_CHAIN + ":" + chain.getChainId(), new SnapshotRef(item, chain, null));
            }
            for (AgentComponentDraftSnapshot draft : snapshotService.listEarliestDraftsByRun(item.getRunId())) {
                refs.put(SNAPSHOT_RESOURCE_COMPONENT_DRAFT + ":" + draft.getComponentId(),
                    new SnapshotRef(item, null, draft));
            }
        }
        return refs;
    }

    /**
     * 按预检口径执行回退：先写回被修改的链路，再删新建链路（引用方优先），最后处理组件。
     *
     * @param refs 每个资源的最早快照
     * @return 每个资源一条结果
     */
    private List<AgentRollbackVo> executeRefs(Map<String, SnapshotRef> refs) {
        List<AgentRollbackVo> result = new ArrayList<>(refs.size());
        List<SnapshotRef> createdChains = new ArrayList<>();
        // 1. 先写回被修改的链路：结构恢复后再判断谁还引用待删除的新链路，避免误判
        for (SnapshotRef ref : refs.values()) {
            if (ref.chain() == null) {
                continue;
            }
            AgentChainSnapshot snapshot = ref.chain();
            if (OPERATION_CREATE.equals(snapshot.getOperation())) {
                createdChains.add(ref);
                continue;
            }
            result.add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), false, restoreChain(ref.run(), snapshot)));
        }
        // 2. 删除新建链路：本轮内互相引用时先删引用方，删不动再逐圈重试
        result.addAll(deleteCreatedChains(createdChains));
        // 3. 组件：新建的整条撤回（被引用的转软退役），修改过的写回旧内容
        for (SnapshotRef ref : refs.values()) {
            if (ref.draft() == null) {
                continue;
            }
            AgentComponentDraftSnapshot snapshot = ref.draft();
            boolean created = OPERATION_CREATE.equals(snapshot.getOperation());
            String reason = created ? deleteCreatedComponent(ref) : restoreDraft(ref);
            result.add(buildVo(SNAPSHOT_RESOURCE_COMPONENT_DRAFT, snapshot.getComponentId(),
                snapshot.getComponentCode(), snapshot.getComponentName(), created, reason));
        }
        return result;
    }

    /**
     * 删除本轮新建的链路：多轮重试，保证「A 引用 B、两条都是本轮新建」时先把 A 删掉再删 B。
     *
     * @param refs 新建链路的快照
     * @return 每条链路一条结果
     */
    private List<AgentRollbackVo> deleteCreatedChains(List<SnapshotRef> refs) {
        Map<Long, SnapshotRef> pending = new LinkedHashMap<>();
        for (SnapshotRef ref : refs) {
            pending.put(ref.chain().getChainId(), ref);
        }
        List<AgentRollbackVo> result = new ArrayList<>(pending.size());
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (Long chainId : new ArrayList<>(pending.keySet())) {
                SnapshotRef ref = pending.get(chainId);
                String reason = deleteCreatedChain(ref);
                if (BLOCKED_BY_REFERENCE.equals(reason)) {
                    // 引用方可能也在待删集合里，留给下一圈
                    continue;
                }
                AgentChainSnapshot snapshot = ref.chain();
                result.add(buildVo(SNAPSHOT_RESOURCE_CHAIN, chainId, snapshot.getChainCode(),
                    snapshot.getChainName(), true, reason));
                pending.remove(chainId);
                progress = true;
            }
        }
        // 引用方全在回退范围外：确实删不掉，带上引用方名字跳过
        for (SnapshotRef ref : pending.values()) {
            AgentChainSnapshot snapshot = ref.chain();
            result.add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), true,
                "仍被链路引用（" + describeReferrers(
                    findReferencedChains(snapshot.getChainCode(), snapshot.getChainId())) + "），无法撤销新建"));
        }
        return result;
    }

    /**
     * 链路预检：删除 / 写回 / 跳过。新建链路的删除不再受发布状态限制，只受回退范围外的引用限制。
     */
    private void classifyChain(AgentRevertPreviewVo preview, Map<Long, AgentRollbackVo> affected,
                               SnapshotRef ref, Set<Long> revertedChainIds) {
        AgentChainSnapshot snapshot = ref.chain();
        DatabusAgentRun run = ref.run();
        boolean created = OPERATION_CREATE.equals(snapshot.getOperation());
        DatabusChainVo chain = chainService.queryById(snapshot.getChainId());
        if (chain == null) {
            preview.getSkip().add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), created, "链路已不存在"));
            return;
        }
        if (OWNER_SYSTEM.equals(chain.getOwnerType())
            || !Objects.equals(chain.getCreateBy(), run.getUserId())) {
            preview.getSkip().add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), created, "链路已归属系统或其他用户，无法回退"));
            return;
        }
        if (!created) {
            // 写回旧结构会改变引用方的契约，跨范围引用照实告知，但不阻断
            List<DatabusChain> external = externalReferrers(snapshot.getChainCode(), snapshot.getChainId(),
                revertedChainIds);
            collectAffected(affected, external, "引用了该链路，回退后按旧结构运行");
            preview.getRevert().add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), false, null));
            return;
        }
        List<DatabusChain> external = externalReferrers(snapshot.getChainCode(), snapshot.getChainId(),
            revertedChainIds);
        if (!external.isEmpty()) {
            preview.getSkip().add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
                snapshot.getChainName(), true,
                "仍被链路引用（" + describeReferrers(external) + "），无法撤销新建"));
            return;
        }
        preview.getRevert().add(buildVo(SNAPSHOT_RESOURCE_CHAIN, snapshot.getChainId(), snapshot.getChainCode(),
            snapshot.getChainName(), true, null));
    }

    /**
     * 组件预检：无论草稿还是已采纳都撤回。新建的被引用时转为软退役（链路照常运行），
     * 修改过的写回旧内容并告知引用方行为变化。
     */
    private void classifyDraft(AgentRevertPreviewVo preview, Map<Long, AgentRollbackVo> affected,
                               SnapshotRef ref, Set<Long> revertedChainIds) {
        AgentComponentDraftSnapshot snapshot = ref.draft();
        DatabusAgentRun run = ref.run();
        boolean created = OPERATION_CREATE.equals(snapshot.getOperation());
        DatabusComponentVo component = componentService.queryById(snapshot.getComponentId());
        if (component == null) {
            preview.getSkip().add(buildVo(SNAPSHOT_RESOURCE_COMPONENT_DRAFT, snapshot.getComponentId(),
                snapshot.getComponentCode(), snapshot.getComponentName(), created, "组件已不存在"));
            return;
        }
        if (!Objects.equals(component.getCreateBy(), run.getUserId())) {
            preview.getSkip().add(buildVo(SNAPSHOT_RESOURCE_COMPONENT_DRAFT, snapshot.getComponentId(),
                snapshot.getComponentCode(), snapshot.getComponentName(), created, "组件已归属其他用户，无法回退"));
            return;
        }
        List<DatabusChain> external = externalReferrers(snapshot.getComponentCode(), null, revertedChainIds);
        if (!created) {
            collectAffected(affected, external, "引用了该组件，回退后按旧版本内容运行");
            preview.getRevert().add(buildVo(SNAPSHOT_RESOURCE_COMPONENT_DRAFT, snapshot.getComponentId(),
                snapshot.getComponentCode(), snapshot.getComponentName(), false, null));
            return;
        }
        // 删除会按引用自动转软退役，所以被引用不再是「撤不掉」，只是影响面要提前说清楚
        collectAffected(affected, external, "引用了该组件，回退后组件转为停用，链路仍可运行");
        preview.getRevert().add(buildVo(SNAPSHOT_RESOURCE_COMPONENT_DRAFT, snapshot.getComponentId(),
            snapshot.getComponentCode(), snapshot.getComponentName(), true, null));
    }

    /**
     * 把快照里的链路结构整条写回（回滚的 UPDATE 分支）。
     *
     * @param run      当前 run
     * @param snapshot 本 run 内该链路最早一份快照
     * @return null 表示写回成功；否则为跳过原因
     */
    private String restoreChain(DatabusAgentRun run, AgentChainSnapshot snapshot) {
        DatabusChainBo bo = new DatabusChainBo();
        bo.setId(snapshot.getChainId());
        bo.setChainCode(snapshot.getChainCode());
        bo.setChainName(snapshot.getChainName());
        bo.setCmpProperty(snapshot.getCmpProperty());
        bo.setCanvasData(snapshot.getCanvasData());
        bo.setInputParams(snapshot.getInputParams());
        bo.setLogLevel(snapshot.getLogLevel());
        bo.setRemark(snapshot.getRemark());
        bo.setUpdateBy(run.getUserId());
        try {
            if (!Boolean.TRUE.equals(chainService.updateByBo(bo, run.getUserId()))) {
                return "链路已不存在，无法写回";
            }
        } catch (Exception e) {
            log.warn("[databus-agent] 写回链路失败 runId={} chainId={}：{}", run.getRunId(), snapshot.getChainId(),
                e.getMessage());
            return "链路已变更，无法写回";
        }
        return null;
    }

    /**
     * 撤销一次新建链路：本人创建即可整条删除，草稿态与已发布态同等对待。
     *
     * @param ref 新建链路快照
     * @return null 表示已删除；BLOCKED 表示仍有引用方；其余为跳过原因
     */
    private String deleteCreatedChain(SnapshotRef ref) {
        AgentChainSnapshot snapshot = ref.chain();
        DatabusChainVo chain = chainService.queryById(snapshot.getChainId());
        if (chain == null) {
            return "链路已不存在";
        }
        if (OWNER_SYSTEM.equals(chain.getOwnerType())
            || !Objects.equals(chain.getCreateBy(), ref.run().getUserId())) {
            return "链路已归属系统或其他用户，无法撤销";
        }
        if (!findReferencedChains(snapshot.getChainCode(), snapshot.getChainId()).isEmpty()) {
            return BLOCKED_BY_REFERENCE;
        }
        try {
            return Boolean.TRUE.equals(chainService.deleteByIds(List.of(snapshot.getChainId())))
                ? null : "链路已不存在";
        } catch (Exception e) {
            // 预检与删除之间可能被并发加上引用：留到下一圈重新判断
            log.warn("[databus-agent] 撤销新建失败 runId={} chainId={}：{}", ref.run().getRunId(),
                snapshot.getChainId(), e.getMessage());
            return BLOCKED_BY_REFERENCE;
        }
    }

    /**
     * 把快照里的组件内容写回（回滚的 UPDATE 分支）。
     * <p>
     * 草稿复用草稿保存；已采纳/已停用的组件走脚本保存管线（重新编译 + 热替换）写回正文，
     * 再走通用编辑写回名称/分组/描述——撤回采纳后的改动同样是回退的份内事。
     *
     * @param ref 组件快照
     * @return null 表示写回成功；否则为跳过原因
     */
    private String restoreDraft(SnapshotRef ref) {
        AgentComponentDraftSnapshot snapshot = ref.draft();
        DatabusComponentVo component = componentService.queryById(snapshot.getComponentId());
        if (component == null) {
            return "组件已不存在，无法写回";
        }
        if (!Objects.equals(component.getCreateBy(), ref.run().getUserId())) {
            return "组件已归属其他用户，无法写回";
        }
        if (ComponentStatusEnum.DRAFT.getCode().equals(component.getStatus())) {
            return saveDraftContent(ref.run(), snapshot);
        }
        try {
            ScriptSaveBo script = new ScriptSaveBo();
            script.setId(snapshot.getComponentId());
            script.setScriptBody(snapshot.getScriptBody());
            componentService.saveScript(script);

            DatabusComponentBo meta = new DatabusComponentBo();
            meta.setId(snapshot.getComponentId());
            meta.setComponentCode(snapshot.getComponentCode());
            meta.setComponentName(snapshot.getComponentName());
            meta.setCategory(snapshot.getCategory());
            meta.setGroupName(snapshot.getGroupName());
            meta.setDescription(snapshot.getDescription());
            componentService.updateByBo(meta);
        } catch (Exception e) {
            log.warn("[databus-agent] 写回已采纳组件失败 runId={} componentId={}：{}", ref.run().getRunId(),
                snapshot.getComponentId(), e.getMessage());
            return "组件已变更，无法写回";
        }
        return null;
    }

    /**
     * 草稿写回：正文重新编译一次，顺带把名称/分组/描述/入参一并写回。
     */
    private String saveDraftContent(DatabusAgentRun run, AgentComponentDraftSnapshot snapshot) {
        ScriptDraftBo bo = new ScriptDraftBo();
        bo.setId(snapshot.getComponentId());
        bo.setComponentName(snapshot.getComponentName());
        bo.setCategory(snapshot.getCategory());
        bo.setGroupName(snapshot.getGroupName());
        bo.setDescription(snapshot.getDescription());
        bo.setScriptBody(snapshot.getScriptBody());
        bo.setInputParams(snapshot.getInputParams());
        bo.setCreateBy(run.getUserId());
        try {
            componentService.saveScriptDraft(bo);
        } catch (Exception e) {
            log.warn("[databus-agent] 写回草稿失败 runId={} componentId={}：{}", run.getRunId(),
                snapshot.getComponentId(), e.getMessage());
            return "草稿已变更，无法写回";
        }
        return null;
    }

    /**
     * 撤销一次组件新建：草稿与已采纳同等对待，整条撤回。
     * <p>
     * 走组件删除而非 rejectScriptDraft：未被引用的物理删除，被引用的转软退役，
     * 存量链路继续按原编码运行，不会因为回退把运行中的链路弄挂。
     *
     * @param ref 新建组件快照
     * @return null 表示已撤回；否则为跳过原因
     */
    private String deleteCreatedComponent(SnapshotRef ref) {
        AgentComponentDraftSnapshot snapshot = ref.draft();
        DatabusComponentVo component = componentService.queryById(snapshot.getComponentId());
        if (component == null) {
            return "组件已不存在";
        }
        if (!Objects.equals(component.getCreateBy(), ref.run().getUserId())) {
            return "组件已归属其他用户，无法撤销";
        }
        try {
            return Boolean.TRUE.equals(componentService.deleteByIds(List.of(snapshot.getComponentId())))
                ? null : "组件已不存在";
        } catch (Exception e) {
            log.warn("[databus-agent] 撤销组件新建失败 runId={} componentId={}：{}", ref.run().getRunId(),
                snapshot.getComponentId(), e.getMessage());
            return "组件删除失败";
        }
    }

    /**
     * 回退范围之外的引用方：范围外的改动不受本次回退控制，删除/写回会影响它们，需要列进影响清单。
     */
    private List<DatabusChain> externalReferrers(String code, Long excludeId, Set<Long> revertedChainIds) {
        return findReferencedChains(code, excludeId).stream()
            .filter(row -> !revertedChainIds.contains(row.getId()))
            .toList();
    }

    /**
     * 找出引用了指定编码的链路（cmp_property 精确匹配 {@code "id":"<编码>"}），用于影响清单。
     *
     * @param code      链路编码 / 组件编码
     * @param excludeId 需要排除的链路 id（删除自身时避免自引用）
     * @return 引用方链路（只带 id / 编码 / 名称）
     */
    private List<DatabusChain> findReferencedChains(String code, Long excludeId) {
        if (code == null || code.isBlank()) {
            return List.of();
        }
        List<DatabusChain> rows = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .select(DatabusChain::getId, DatabusChain::getChainCode, DatabusChain::getChainName)
            .like(DatabusChain::getCmpProperty, "\"id\":\"" + escapeLike(code) + "\""));
        return rows.stream().filter(row -> !Objects.equals(row.getId(), excludeId)).toList();
    }

    /**
     * 把引用方链路收集进受影响清单（按链路 id 去重）。
     *
     * @param affected  受影响清单
     * @param referrers 引用方链路
     * @param reason    影响说明
     */
    private void collectAffected(Map<Long, AgentRollbackVo> affected, List<DatabusChain> referrers, String reason) {
        for (DatabusChain row : referrers) {
            affected.computeIfAbsent(row.getId(), key -> {
                AgentRollbackVo vo = new AgentRollbackVo();
                vo.setResourceType(SNAPSHOT_RESOURCE_CHAIN);
                vo.setResourceId(row.getId());
                vo.setResourceCode(row.getChainCode());
                vo.setResourceName(row.getChainName());
                vo.setAction(REVERT_ACTION_REFERENCE);
                vo.setReason(reason);
                return vo;
            });
        }
    }

    /** 引用方链路名，最多列三条，避免跳过原因过长 */
    private String describeReferrers(List<DatabusChain> referrers) {
        if (referrers.isEmpty()) {
            return "其他链路";
        }
        String names = referrers.stream()
            .limit(3)
            .map(DatabusChain::getChainName)
            .collect(Collectors.joining("、"));
        return referrers.size() > 3 ? names + " 等 " + referrers.size() + " 条" : names;
    }

    /**
     * 组装回退条目：有跳过原因即 SKIP，否则按新建/修改给出 DELETE / RESTORE。
     */
    private AgentRollbackVo buildVo(String resourceType, Long resourceId, String resourceCode,
                                    String resourceName, boolean created, String skipReason) {
        AgentRollbackVo vo = new AgentRollbackVo();
        vo.setResourceType(resourceType);
        vo.setResourceId(resourceId);
        vo.setResourceCode(resourceCode);
        vo.setResourceName(resourceName);
        if (skipReason != null) {
            vo.setAction(REVERT_ACTION_SKIP);
            vo.setDeleted(false);
            vo.setReason(skipReason);
        } else {
            vo.setAction(created ? REVERT_ACTION_DELETE : REVERT_ACTION_RESTORE);
            vo.setDeleted(created);
        }
        return vo;
    }

    /** 转义 MySQL LIKE 通配符，与组件删除的引用检测保持同一口径 */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /**
     * 一条最早快照及其所属 run（链路与草稿二选一）。
     */
    private record SnapshotRef(DatabusAgentRun run, AgentChainSnapshot chain, AgentComponentDraftSnapshot draft) {
    }
}