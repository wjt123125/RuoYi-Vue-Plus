package org.dromara.databus.agent;

import com.aizuda.snail.ai.agent.common.context.AgentChatContextHolder;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具身份守卫（方案 §十五 守卫 ②–⑤）。
 * <p>
 * 身份不靠模型自报：只取 SnailAI 透传的 conversationId，反查 databus_agent_run
 * 拿到 userId / chainId / permissions 快照。任何一处对不上都拒绝，绝不退化成放行。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentGuard {

    /**
     * 同一会话下多个进行中任务无法唯一定位身份，直接拒绝。
     */
    private static final int SINGLE_RUNNING = 1;

    private final DatabusAgentRunMapper runMapper;

    /**
     * 执行守卫校验并返回身份快照。
     *
     * @param permission    所需权限点，空表示不校验权限
     * @param targetChainId 目标链路 id；写操作必传，读操作传 null 表示不校验链路绑定
     * @return 身份快照
     * @throws AgentGuardRejectedException 任一守卫不通过
     */
    public AgentRunContext check(String permission, Long targetChainId) {
        // ② 上下文存在：取 SnailAI 透传的 conversationId
        String conversationId = AgentChatContextHolder.getContext().getConversationId();
        if (!StringUtils.hasText(conversationId)) {
            throw new AgentGuardRejectedException(null, "缺少会话上下文（conversationId 为空），已拒绝执行");
        }

        // ② 反查 run：必须且只能有一条进行中任务
        List<DatabusAgentRun> running = runMapper.selectList(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .orderByDesc(DatabusAgentRun::getCreateTime));
        if (running.isEmpty()) {
            throw new AgentGuardRejectedException(null, "会话 " + conversationId + " 没有进行中的任务，已拒绝执行");
        }
        if (running.size() > SINGLE_RUNNING) {
            throw new AgentGuardRejectedException(null, "会话 " + conversationId + " 存在多个进行中的任务，无法确定调用身份，已拒绝执行");
        }

        DatabusAgentRun run = running.get(0);

        // ⑤ 归属时效：过期任务就地置 EXPIRED
        if (run.getExpireTime() != null && run.getExpireTime().isBefore(LocalDateTime.now())) {
            stopExpired(run.getRunId());
            throw new AgentGuardRejectedException(run.getRunId(), "任务已过期，已拒绝执行");
        }
        if (run.getUserId() == null) {
            throw new AgentGuardRejectedException(run.getRunId(), "任务缺少发起人快照，已拒绝执行");
        }

        // ③ 权限点校验：用发起时的快照，不看当前登录态
        List<String> permissions;
        try {
            permissions = JsonUtils.parseArray(run.getPermissions(), String.class);
        } catch (Exception e) {
            throw new AgentGuardRejectedException(run.getRunId(), "任务权限快照损坏，已拒绝执行");
        }
        AgentRunContext context = new AgentRunContext(
            run.getRunId(),
            conversationId,
            run.getUserId(),
            run.getChainId(),
            permissions,
            run.getRound() == null ? 0 : run.getRound());
        if (!context.hasPermission(permission)) {
            throw new AgentGuardRejectedException(run.getRunId(), "当前用户缺少权限点 " + permission + "，已拒绝执行");
        }

        // ④ 目标校验：任务绑定链路时，目标链路必须与快照一致
        if (targetChainId != null) {
            if (run.getChainId() == null) {
                throw new AgentGuardRejectedException(run.getRunId(), "当前任务未绑定链路，不能操作链路 " + targetChainId);
            }
            if (!run.getChainId().equals(targetChainId)) {
                throw new AgentGuardRejectedException(run.getRunId(),
                    "目标链路 " + targetChainId + " 与当前任务绑定的链路 " + run.getChainId() + " 不一致，已拒绝执行");
            }
        }
        return context;
    }

    /**
     * 首次新建链路后把 run 绑定到新链 id。
     * <p>
     * 只在该 run 尚未绑定链路时写入：后续 chain_save 的 guard ④ 依赖这条绑定，
     * 否则「从零建链」第二轮就会被「任务未绑定链路」拒掉。
     *
     * @param runId   run 句柄
     * @param chainId 新建链路 id
     */
    public void bindChain(String runId, Long chainId) {
        runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .isNull(DatabusAgentRun::getChainId)
            .set(DatabusAgentRun::getChainId, chainId));
    }

    /**
     * 进入下一轮：round 原子自增并回读。
     * <p>
     * round 落在 run 上而不是链路上：链路草稿是用户资产，不该被 AI 的运行状态污染。
     *
     * @param runId run 句柄
     * @return 自增后的轮次（1 起）
     */
    public int nextRound(String runId) {
        runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .setSql("round = round + 1"));
        DatabusAgentRun run = runMapper.selectOne(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getRunId, runId)
            .select(DatabusAgentRun::getRound));
        return run == null || run.getRound() == null ? 1 : run.getRound();
    }

    /**
     * 同步 run.before_payload（最新一份动手前快照；多轮历史见 databus_agent_snapshot）。
     *
     * @param runId   run 句柄
     * @param payload 链路快照 JSON
     */
    public void updateBeforePayload(String runId, String payload) {
        runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .set(DatabusAgentRun::getBeforePayload, payload));
    }

    /**
     * 惰性过期：仅当仍为 RUNNING 时改为 EXPIRED，避免覆盖并发收尾状态。
     *
     * @param runId run 句柄
     */
    private void stopExpired(String runId) {
        int rows = runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .set(DatabusAgentRun::getStatus, AgentRunStatusEnum.EXPIRED.getCode()));
        log.info("[databus-agent] 工具调用发现任务过期 runId={} affected={}", runId, rows);
    }

}