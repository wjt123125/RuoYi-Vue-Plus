package org.dromara.databus.agent.run;

import org.dromara.databus.agent.event.AgentSystemEventCatalog;
import org.dromara.databus.agent.support.AgentConversationMetaSupport;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.DatabusAgentStep;
import org.dromara.databus.domain.bo.AgentSystemEventBo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.mapper.DatabusAgentStepMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.dromara.databus.constant.AgentConstants.CONVERSATION_HISTORY_LIMIT;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD_REPLY;
import static org.dromara.databus.constant.AgentConstants.EVENT_ERROR;
import static org.dromara.databus.constant.AgentConstants.EVENT_FOLLOW_UPS;
import static org.dromara.databus.constant.AgentConstants.EVENT_SYSTEM_EVENT;
import static org.dromara.databus.constant.AgentConstants.PREVIOUS_SUMMARY_LIMIT;
import static org.dromara.databus.constant.AgentConstants.STEP_DIGEST_LIMIT;

/**
 * 历史回放与模型上下文拼装：run / message / step 的只读查询、run 归属校验、回放 VO 补齐。
 * <p>
 * 只读不写（唯一的写路径是调用方的条件 UPDATE），不推送 SSE、也不做 run 状态机决策。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class AgentRunReplayAssembler {

    /**
     * 摘要脱敏：token / password / secret / api-key / authorization 的值一律打码。
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)(\"?(?:token|password|passwd|secret|api[-_]?key|authorization)\"?\\s*[:=]\\s*\"?)([^\",}\\s]+)");

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final DatabusAgentStepMapper stepMapper;
    private final AgentConversationMetaSupport conversationSupport;

    /**
     * 按会话查当前用户进行中的 run（刷新后恢复用），无则返回 null。
     *
     * @param conversationId 会话 id
     * @return 进行中的 run
     */
    public AgentRunVo getActiveRun(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        // 已删会话不再对外暴露运行态，避免刷新后被恢复成一条会话
        if (conversationSupport.isConversationDeleted(conversationId)) {
            return null;
        }
        Long userId = LoginHelper.getUserId();
        DatabusAgentRun run = runMapper.selectOne(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .orderByDesc(DatabusAgentRun::getCreateTime)
            .last("limit 1"));
        if (run == null) {
            return null;
        }
        if (run.getExpireTime() != null && run.getExpireTime().isBefore(LocalDateTime.now())) {
            // 惰性过期：查到就置 EXPIRED，不做扫描任务
            stopExpired(run.getRunId());
            return null;
        }
        return toRunVo(run);
    }

    /**
     * 回放某个 run 已落库的消息（按 seq 升序）。
     *
     * @param runId run 句柄
     * @return 消息列表
     */
    public List<AgentMessageVo> listMessages(String runId) {
        requireOwnedRun(runId);
        return messageMapper.selectVoList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .eq(DatabusAgentMessage::getRunId, runId)
            .orderByAsc(DatabusAgentMessage::getSeq));
    }

    /**
     * 按会话回放历史轮次（按创建时间升序，取最近若干条）。
     * <p>
     * 刷新页面后靠 run 的 intent + report 还原对话，不重放 SSE 事件。
     *
     * @param conversationId 会话 id
     * @return 历史 run 列表
     */
    public List<AgentRunVo> listConversationRuns(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        // 历史回放也认软删：否则刷新时前端会按历史重建本地桶，已删会话又冒出来
        if (conversationSupport.isConversationDeleted(conversationId)) {
            return List.of();
        }
        Long userId = LoginHelper.getUserId();
        // 取最近 N 条再翻转成升序：翻页式截断只该砍最老的，而不是最早那批
        List<DatabusAgentRun> runs = runMapper.selectList(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .ne(DatabusAgentRun::getStatus, AgentRunStatusEnum.SUPERSEDED.getCode())
            .orderByDesc(DatabusAgentRun::getCreateTime)
            .last("limit " + CONVERSATION_HISTORY_LIMIT));
        if (runs.isEmpty()) {
            return List.of();
        }
        Collections.reverse(runs);
        List<AgentRunVo> vos = runs.stream().map(this::toRunVo).toList();
        fillRunExtras(vos);
        return vos;
    }

    /**
     * 校验 run 存在且属于当前用户（run 是不可信输入，红线 15）。
     *
     * @param runId run 句柄
     * @return 任务
     */
    public DatabusAgentRun requireOwnedRun(String runId) {
        if (!StringUtils.hasText(runId)) {
            throw new ServiceException("任务不存在");
        }
        DatabusAgentRun run = runMapper.selectOne(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getRunId, runId));
        if (run == null) {
            throw new ServiceException("任务不存在");
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null || !userId.equals(run.getUserId())) {
            throw new ServiceException("无权访问该任务");
        }
        return run;
    }

    /**
     * 惰性过期：把超时的 RUNNING 置 EXPIRED。
     *
     * @param runId run 句柄
     */
    private void stopExpired(String runId) {
        LambdaUpdateWrapper<DatabusAgentRun> wrapper = Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .set(DatabusAgentRun::getStatus, AgentRunStatusEnum.EXPIRED.getCode())
            .set(DatabusAgentRun::getUpdateTime, LocalDateTime.now());
        runMapper.update(null, wrapper);
    }

    /**
     * 一次批量补齐各 run 的卡片、回执与脱敏错误：刷新后前端据此还原。
     * <p>
     * 工具阶段是瞬时状态，不落库、不回放；审计明细在 databus_agent_step。
     *
     * @param runs 会话内 run 列表
     */
    private void fillRunExtras(List<AgentRunVo> runs) {
        List<String> runIds = runs.stream().map(AgentRunVo::getRunId).toList();
        List<DatabusAgentMessage> messages = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .in(DatabusAgentMessage::getRunId, runIds)
            .in(DatabusAgentMessage::getEventCode, EVENT_CARD, EVENT_CARD_REPLY, EVENT_ERROR, EVENT_FOLLOW_UPS)
            .orderByAsc(DatabusAgentMessage::getSeq));
        Map<String, List<String>> cardsByRun = new LinkedHashMap<>();
        Map<String, List<String>> repliesByRun = new LinkedHashMap<>();
        Map<String, List<String>> errorsByRun = new LinkedHashMap<>();
        Map<String, List<String>> followUpsByRun = new LinkedHashMap<>();
        for (DatabusAgentMessage message : messages) {
            if (EVENT_ERROR.equals(message.getEventCode())) {
                errorsByRun.computeIfAbsent(message.getRunId(), key -> new ArrayList<>())
                    .add(message.getContent());
                continue;
            }
            if (EVENT_FOLLOW_UPS.equals(message.getEventCode())) {
                followUpsByRun.put(message.getRunId(), parseFollowUps(message.getContent()));
                continue;
            }
            boolean isCard = EVENT_CARD.equals(message.getEventCode());
            Map<String, List<String>> target = isCard ? cardsByRun : repliesByRun;
            target.computeIfAbsent(message.getRunId(), key -> new ArrayList<>()).add(message.getContent());
        }
        for (AgentRunVo vo : runs) {
            vo.setCards(cardsByRun.getOrDefault(vo.getRunId(), List.of()));
            vo.setCardReplies(repliesByRun.getOrDefault(vo.getRunId(), List.of()));
            vo.setErrors(errorsByRun.getOrDefault(vo.getRunId(), List.of()));
            vo.setFollowUps(followUpsByRun.getOrDefault(vo.getRunId(), List.of()));
        }
    }

    /**
     * 反序列化追问建议（库里是 JSON 字符串数组）。
     *
     * @param content JSON 字符串
     * @return 建议列表
     */
    private static List<String> parseFollowUps(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        try {
            return JsonUtils.parseArray(content, String.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 组装本轮发给模型的内容：附件引用材料 + 用户问题 + 同一会话上一轮已结束 run 的工具摘要。
     * <p>
     * 顺序固定为「附件块 → 用户问题 → 上一轮摘要」：模型先读引用材料再读问题，最后才是续接线索；
     * 用户只带附件没写原话时，不输出空的「用户问题：」标题。
     * 上一轮摘要取 databus_agent_step 的 tool / args_digest / result_digest，截断并脱敏，
     * 明确标注「上一轮工具摘要」，让模型在用户追问「继续加载下一页」时能拿到 nextCursor。
     *
     * @param run 当前 run
     * @return 发给模型的内容
     */
    public String buildChatContent(DatabusAgentRun run) {
        String intent = run.getIntent() == null ? "" : run.getIntent();
        StringBuilder content = new StringBuilder();
        String attachments = AgentContextAssembler.assemble(run.getAttachments());
        if (StringUtils.hasText(attachments)) {
            content.append(attachments);
        }
        // 系统事件（事实 + 行为要求）只在这里出现，绝不写进 intent，用户界面上看不到
        String events = buildSystemEvents(run);
        if (StringUtils.hasText(events)) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(events);
        }
        if (StringUtils.hasText(intent)) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append("用户问题：").append(intent);
        }
        String summary = buildPreviousRoundSummary(run);
        if (summary != null) {
            if (content.length() > 0) {
                content.append("\n\n");
            }
            content.append(summary);
        }
        return content.toString();
    }

    /**
     * 本轮上报过的系统事件：按 seq 升序取 model 侧事实句，供模型理解「界面之外发生了什么」。
     *
     * @param run 当前 run
     * @return 事实句拼接，没有事件时返回 null
     */
    private String buildSystemEvents(DatabusAgentRun run) {
        List<DatabusAgentMessage> messages = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .eq(DatabusAgentMessage::getRunId, run.getRunId())
            .eq(DatabusAgentMessage::getEventCode, EVENT_SYSTEM_EVENT)
            .orderByAsc(DatabusAgentMessage::getSeq));
        if (messages.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (DatabusAgentMessage message : messages) {
            AgentSystemEventBo event = JsonUtils.parseObject(message.getContent(), AgentSystemEventBo.class);
            if (event == null || !StringUtils.hasText(event.getCode())) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(AgentSystemEventCatalog.resolve(event.getCode(), event.getPayload()).fact());
        }
        return text.length() == 0 ? null : text.toString();
    }

    private String buildPreviousRoundSummary(DatabusAgentRun run) {
        DatabusAgentRun previous = runMapper.selectOne(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, run.getUserId())
            .eq(DatabusAgentRun::getConversationId, run.getConversationId())
            .ne(DatabusAgentRun::getRunId, run.getRunId())
            .ne(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .ne(DatabusAgentRun::getStatus, AgentRunStatusEnum.SUPERSEDED.getCode())
            .orderByDesc(DatabusAgentRun::getCreateTime)
            .last("limit 1"));
        if (previous == null) {
            return null;
        }
        List<DatabusAgentStep> steps = stepMapper.selectList(Wrappers.<DatabusAgentStep>lambdaQuery()
            .eq(DatabusAgentStep::getRunId, previous.getRunId())
            .orderByAsc(DatabusAgentStep::getSeq));
        if (steps.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder();
        summary.append("【上一轮工具摘要】同一会话上一轮任务（runId=").append(previous.getRunId())
            .append("）的调用记录，仅用于延续上下文；其中的 nextCursor 可直接用于继续翻页：");
        for (DatabusAgentStep step : steps) {
            summary.append("\n- ").append(step.getTool())
                .append(" args=").append(truncateRedact(step.getArgsDigest()))
                .append(" result=").append(truncateRedact(step.getResultDigest()));
            if (summary.length() >= PREVIOUS_SUMMARY_LIMIT) {
                summary.append("\n（上一轮工具摘要过长，已截断）");
                break;
            }
        }
        return summary.toString();
    }

    /**
     * 单条摘要脱敏 + 截断（截断明确加省略号，不静默丢内容）。
     *
     * @param digest 原始摘要
     * @return 处理后的摘要
     */
    private static String truncateRedact(String digest) {
        if (!StringUtils.hasText(digest)) {
            return "null";
        }
        String value = SECRET_PATTERN.matcher(digest).replaceAll("$1***");
        return value.length() > STEP_DIGEST_LIMIT ? value.substring(0, STEP_DIGEST_LIMIT) + "…" : value;
    }

    /**
     * 转 VO（手写映射，避免 MapStruct 对运行期同名字段做多余推断）。
     *
     * @param run 任务
     * @return VO
     */
    private AgentRunVo toRunVo(DatabusAgentRun run) {
        AgentRunVo vo = new AgentRunVo();
        vo.setRunId(run.getRunId());
        vo.setConversationId(run.getConversationId());
        vo.setIntent(run.getIntent());
        vo.setAttachments(run.getAttachments());
        vo.setStatus(run.getStatus());
        vo.setReport(run.getReport());
        vo.setRound(run.getRound());
        vo.setExpireTime(run.getExpireTime());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }
}
