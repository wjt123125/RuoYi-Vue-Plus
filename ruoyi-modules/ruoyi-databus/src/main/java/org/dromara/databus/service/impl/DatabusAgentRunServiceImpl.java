package org.dromara.databus.service.impl;

import cn.hutool.core.lang.Dict;
import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatStreamEvent;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserRegisterRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserVO;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiUserClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.agent.AgentChainSnapshot;
import org.dromara.databus.agent.AgentStepRecorder;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.DatabusAgentStep;
import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.mapper.DatabusAgentStepMapper;
import org.dromara.databus.service.IDatabusAgentRunService;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.system.api.model.LoginUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * AI agent 任务服务实现。
 * <p>
 * 说话链路：前端 POST 建 run → 前端打开 SSE → 本服务调 OpenApiChatClient.chatStream（HTTP 到 SnailAI Server）
 * → Server 经 gRPC 派回本应用的进程内执行器（带 @Tool）→ 事件原路回流到本服务 → 转 SSE 给前端。
 * run 的收尾一律走条件 UPDATE（status=RUNNING 才改），保证 done / 断开 / 停止三者只有一方生效。
 *
 * @author databus
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "snail-ai.open-api", name = "enabled", havingValue = "true")
public class DatabusAgentRunServiceImpl implements IDatabusAgentRunService {

    /**
     * 收口消息的事件编码（推给前端的最终文字，同时用于刷新回放）。
     */
    private static final String EVENT_RUN_END = "databus:agent:run-end";

    /**
     * SnailAI 返回的成功状态码。
     */
    private static final int SNAIL_AI_SUCCESS = 1;

    /**
     * 会话历史最多回放多少轮（一次刷新不该拉全量历史）。
     */
    private static final int CONVERSATION_HISTORY_LIMIT = 50;

    /**
     * 会话列表默认 / 最大条数。
     */
    private static final int DEFAULT_CONVERSATION_LIMIT = 50;
    private static final int MAX_CONVERSATION_LIMIT = 100;

    /**
     * 快照 operation=CREATE：该轮回滚的语义是删除整条新建链路，而不是写回结构。
     */
    private static final String OPERATION_CREATE = "CREATE";
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String OWNER_SYSTEM = "SYSTEM";

    /**
     * 会话标题清洗后的上限：只防超长传输，真正的省略交给前端按栏宽做。
     */
    private static final int TITLE_LIMIT = 200;

    /**
     * 标题清洗用：整段代码块、行首 markdown 记号、行内反引号/星号、连续空白。
     * <p>
     * 刻意不动下划线——用户问句里常有 {@code chain_save}、{@code field_mapping} 这类标识符。
     */
    private static final Pattern TITLE_CODE_BLOCK = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern TITLE_LINE_PREFIX = Pattern.compile("(?m)^\\s*(?:#{1,6}|[-*+]|>)\\s+");
    private static final Pattern TITLE_INLINE_MARK = Pattern.compile("[`*]+");
    private static final Pattern TITLE_BLANK = Pattern.compile("\\s+");

    /**
     * 会话 id 白名单：只接受 UUID / 业务自生成的安全字符，拒绝把任意串塞进 SnailAI 会话（红线 15）。
     */
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    /**
     * 上一轮工具摘要总长度上限（超出明确标注截断，不静默丢弃）。
     */
    private static final int PREVIOUS_SUMMARY_LIMIT = 4000;

    /**
     * 单条工具入参 / 返回摘要长度上限。
     */
    private static final int STEP_DIGEST_LIMIT = 500;

    /**
     * 摘要脱敏：token / password / secret / api-key / authorization 的值一律打码。
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)(\"?(?:token|password|passwd|secret|api[-_]?key|authorization)\"?\\s*[:=]\\s*\"?)([^\",}\\s]+)");

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final DatabusAgentStepMapper stepMapper;
    private final IDatabusAgentSnapshotService snapshotService;
    private final IDatabusChainService chainService;
    private final AgentStepRecorder stepRecorder;
    private final OpenApiChatClient chatClient;
    private final OpenApiUserClient userClient;
    private final OpenApiAgentClient agentClient;
    private final DatabusAgentProperties properties;
    private final TransactionTemplate transactionTemplate;

    /**
     * userId → openId 缓存（注册接口本身幂等，缓存只为省一次 HTTP）。
     */
    private final Map<Long, String> openIdCache = new ConcurrentHashMap<>();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentChatStartVo startChat(AgentChatBo bo) {
        Long userId = LoginHelper.getUserId();
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (userId == null || loginUser == null) {
            throw new ServiceException("当前登录用户为空");
        }
        if (properties.getAgentId() == null) {
            throw new ServiceException("AI 助手未配置 databus.agent.agent-id");
        }
        String conversationId = resolveConversationId(bo.getConversationId());
        supersedeActiveRuns(userId, conversationId);

        DatabusAgentRun run = new DatabusAgentRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setUserId(userId);
        run.setIntent(bo.getContent());
        run.setConversationId(conversationId);
        run.setRound(0);
        run.setStatus(AgentRunStatusEnum.RUNNING.getCode());
        run.setSideEffectApproved("0");
        run.setPermissions(JsonUtils.toJsonString(snapshotPermissions(loginUser)));
        run.setExpireTime(LocalDateTime.now().plusMinutes(properties.getRunTtlMinutes()));
        run.setNextSeq(1);
        // 红线 3：创建人显式落本人，不依赖填充器
        run.setCreateBy(userId);
        runMapper.insert(run);
        return new AgentChatStartVo(run.getRunId(), conversationId);
    }

    @Override
    public SseEmitter openStream(String runId) {
        DatabusAgentRun run = requireOwnedRun(runId);
        if (!AgentRunStatusEnum.RUNNING.getCode().equals(run.getStatus())) {
            throw new ServiceException("任务已结束，无法继续对话");
        }
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutMs());
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicBoolean clientGone = new AtomicBoolean(false);

        Disposable[] subscription = new Disposable[1];
        emitter.onCompletion(() -> dispose(subscription));
        emitter.onTimeout(() -> {
            dispose(subscription);
            settle(settled, run, AgentRunStatusEnum.STOPPED, null);
            emitter.complete();
        });
        emitter.onError(e -> {
            clientGone.set(true);
            settle(settled, run, AgentRunStatusEnum.STOPPED, null);
        });

        // SnailAI 不可达也要走 SSE 的 error 事件，不能抛异常让浏览器只看到「连接已断开」
        OpenApiChatRequest request = new OpenApiChatRequest();
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            request.setAgentId(properties.getAgentId());
            request.setOpenId(resolveOpenId(run.getUserId(), loginUser == null ? null : loginUser.getNickname()));
            request.setConversationId(run.getConversationId());
            // 跨 run 续接：本轮 intent + 上一轮工具摘要（含 nextCursor 等关键词），否则「继续加载下一页」拿不到游标
            request.setContent(buildChatContent(run));
        } catch (Exception e) {
            log.warn("准备 AI 对话失败 runId={}", runId, e);
            settle(settled, run, AgentRunStatusEnum.FAILED, null);
            sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                JsonUtils.toJsonString(Map.of("message", "AI 服务不可用，请稍后重试")));
            emitter.complete();
            return emitter;
        }

        try {
            subscription[0] = chatClient.chatStream(request).subscribe(
                event -> {
                    if (settled.get()) {
                        return;
                    }
                    String type = event.getType();
                    String data = event.getData() == null ? "" : event.getData();
                    if (OpenApiChatStreamEvent.TYPE_TEXT.equals(type) || OpenApiChatStreamEvent.TYPE_THINKING.equals(type)) {
                        data = extractContent(data);
                    }
                    send(emitter, runId, type, data);
                    if (OpenApiChatStreamEvent.TYPE_DONE.equals(type)) {
                        settle(settled, run, AgentRunStatusEnum.FINISHED, extractDoneText(data));
                        emitter.complete();
                    } else if (OpenApiChatStreamEvent.TYPE_ERROR.equals(type)) {
                        settle(settled, run, AgentRunStatusEnum.FAILED, null);
                        emitter.complete();
                    }
                },
                error -> {
                    // 只有 emitter 自己报错才是浏览器断开；上游把流掐断也常以 IOException 出现，不能一并当作客户端消失
                    boolean byClient = clientGone.get();
                    settle(settled, run, byClient ? AgentRunStatusEnum.STOPPED : AgentRunStatusEnum.FAILED, null);
                    if (!byClient) {
                        log.warn("AI 对话流异常 runId={}", runId, error);
                        sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                            JsonUtils.toJsonString(Map.of("message", "AI 服务异常，请稍后重试")));
                    }
                    emitter.complete();
                },
                () -> {
                    if (settled.get()) {
                        return;
                    }
                    // 上游没发 done 就直接结束（如拒绝对话），不能静默收尾，否则浏览器只能看到「连接已断开」
                    log.warn("AI 对话流未收到 done 即结束 runId={}", runId);
                    settle(settled, run, AgentRunStatusEnum.FAILED, null);
                    sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                        JsonUtils.toJsonString(Map.of("message", "AI 服务异常，请稍后重试")));
                    emitter.complete();
                }
            );
        } catch (Exception e) {
            log.warn("发起 AI 对话失败 runId={}", runId, e);
            settle(settled, run, AgentRunStatusEnum.FAILED, null);
            sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                JsonUtils.toJsonString(Map.of("message", "AI 服务不可用，请稍后重试")));
            emitter.complete();
        }
        return emitter;
    }

    @Override
    public void stop(String runId) {
        requireOwnedRun(runId);
        LambdaUpdateWrapper<DatabusAgentRun> wrapper = Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .set(DatabusAgentRun::getStatus, AgentRunStatusEnum.STOPPED.getCode())
            .set(DatabusAgentRun::getUpdateTime, LocalDateTime.now());
        runMapper.update(null, wrapper);
    }

    @Override
    public void reportCanvasSkip(String runId, String chainName) {
        requireOwnedRun(runId);
        String name = StringUtils.hasText(chainName) ? chainName : "当前链路";
        // 借审计 step 告知模型：本轮画布更新被人丢弃，下一轮不要再重复推同一份改动
        stepRecorder.recordReject(runId, "canvas_skip",
            JsonUtils.toJsonString(Map.of("chainName", name)),
            JsonUtils.toJsonString(Map.of("message", "用户丢弃了本次画布更新，画布上是用户未保存的本地改动")),
            "用户丢弃画布更新", 0);
    }

    @Override
    public AgentRunVo getActiveRun(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
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

    @Override
    public List<AgentMessageVo> listMessages(String runId) {
        requireOwnedRun(runId);
        return messageMapper.selectVoList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .eq(DatabusAgentMessage::getRunId, runId)
            .orderByAsc(DatabusAgentMessage::getSeq));
    }

    @Override
    public List<AgentRunVo> listConversationRuns(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
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
        return runs.stream().map(this::toRunVo).toList();
    }

    @Override
    public List<AgentConversationVo> listConversations(Integer limit) {
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new ServiceException("当前登录用户为空");
        }
        int size = limit == null || limit <= 0
            ? DEFAULT_CONVERSATION_LIMIT
            : Math.min(limit, MAX_CONVERSATION_LIMIT);
        List<AgentConversationVo> conversations = runMapper.selectConversations(userId, size);
        conversations.forEach(vo -> vo.setTitle(cleanTitle(vo.getTitle())));
        return conversations;
    }

    /**
     * 把首问原文清洗成一行可展示的标题：去掉代码块与 markdown 记号、合并换行。
     * <p>
     * 只清洗不概括，所以问句长了还是会被前端按栏宽省略——这是刻意的，
     * 不引入模型调用，失败面为零。
     *
     * @param raw 首问原文
     * @return 清洗后的标题，清洗后为空返回 null（前端显示「未命名会话」）
     */
    private static String cleanTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String title = TITLE_CODE_BLOCK.matcher(raw).replaceAll(" ");
        title = TITLE_LINE_PREFIX.matcher(title).replaceAll("");
        title = TITLE_INLINE_MARK.matcher(title).replaceAll("");
        title = TITLE_BLANK.matcher(title).replaceAll(" ").trim();
        if (title.isEmpty()) {
            return null;
        }
        return title.length() > TITLE_LIMIT ? title.substring(0, TITLE_LIMIT) : title;
    }

    @Override
    public List<AgentRollbackVo> rollbackRun(String runId) {
        DatabusAgentRun run = requireOwnedRun(runId);
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

    /**
     * 组装本轮发给模型的内容：本轮 intent + 同一会话上一轮已结束 run 的工具摘要。
     * <p>
     * 上一轮摘要取 databus_agent_step 的 tool / args_digest / result_digest，截断并脱敏，
     * 明确标注「上一轮工具摘要」，让模型在用户追问「继续加载下一页」时能拿到 nextCursor。
     *
     * @param run 当前 run
     * @return 发给模型的内容
     */
    private String buildChatContent(DatabusAgentRun run) {
        String intent = run.getIntent() == null ? "" : run.getIntent();
        String summary = buildPreviousRoundSummary(run);
        return summary == null ? intent : intent + "\n\n" + summary;
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
     * 收尾：条件 UPDATE（RUNNING 才改），done 时把最终文字落一条 message（seq 走 run.next_seq 号源）。
     *
     * @param settled 是否已收尾（同一 run 只允许一次）
     * @param run     任务
     * @param status  目标状态
     * @param report  最终文字，可为空
     */
    private void settle(AtomicBoolean settled, DatabusAgentRun run, AgentRunStatusEnum status, String report) {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        transactionTemplate.executeWithoutResult(tx -> {
            LambdaUpdateWrapper<DatabusAgentRun> wrapper = Wrappers.<DatabusAgentRun>lambdaUpdate()
                .eq(DatabusAgentRun::getRunId, run.getRunId())
                .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
                .set(DatabusAgentRun::getStatus, status.getCode())
                .set(DatabusAgentRun::getUpdateTime, LocalDateTime.now());
            if (StringUtils.hasText(report)) {
                wrapper.set(DatabusAgentRun::getReport, report);
            }
            runMapper.update(null, wrapper);
            if (StringUtils.hasText(report)) {
                // 同一会话可能改过多条链，收口消息归属到本轮最后一条链路
                Long lastChainId = runMapper.selectLastChainId(run.getRunId());
                appendMessage(run.getRunId(), EVENT_RUN_END, lastChainId, report);
            }
        });
    }

    /**
     * 落一条 message 并占用一个 seq（须在事务内调用，靠 run 行锁保序）。
     *
     * @param runId     run 句柄
     * @param eventCode 事件编码
     * @param chainId   链路 id，可为空
     * @param content   载荷
     */
    private void appendMessage(String runId, String eventCode, Long chainId, String content) {
        runMapper.increaseNextSeq(runId);
        Integer nextSeq = runMapper.selectNextSeq(runId);
        DatabusAgentMessage message = new DatabusAgentMessage();
        message.setRunId(runId);
        message.setSeq(nextSeq == null ? 1 : nextSeq - 1);
        message.setEventCode(eventCode);
        message.setChainId(chainId);
        message.setContent(content);
        messageMapper.insert(message);
    }

    /**
     * 同一会话的进行中 run 置 SUPERSEDED，保证每会话同时只有一条在跑；跨会话互不影响。
     *
     * @param userId         发起人
     * @param conversationId 会话 id
     */
    private void supersedeActiveRuns(Long userId, String conversationId) {
        LambdaUpdateWrapper<DatabusAgentRun> wrapper = Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .set(DatabusAgentRun::getStatus, AgentRunStatusEnum.SUPERSEDED.getCode())
            .set(DatabusAgentRun::getUpdateTime, LocalDateTime.now());
        runMapper.update(null, wrapper);
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
     * 校验 run 存在且属于当前用户（run 是不可信输入，红线 15）。
     *
     * @param runId run 句柄
     * @return 任务
     */
    private DatabusAgentRun requireOwnedRun(String runId) {
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
     * 会话 id：为空则新开，非空必须是安全字符（拒绝任意串直传 SnailAI）。
     *
     * @param conversationId 前端传来的会话 id
     * @return 合法会话 id
     */
    private String resolveConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return UUID.randomUUID().toString();
        }
        if (!CONVERSATION_ID_PATTERN.matcher(conversationId).matches()) {
            throw new ServiceException("会话 id 不合法");
        }
        return conversationId;
    }

    /**
     * 权限点快照：菜单权限 + 角色权限（工具层据此鉴权，不接受入参里的身份）。
     *
     * @param loginUser 登录用户
     * @return 权限点列表
     */
    private List<String> snapshotPermissions(LoginUser loginUser) {
        List<String> permissions = new ArrayList<>();
        if (loginUser.getMenuPermission() != null) {
            permissions.addAll(loginUser.getMenuPermission());
        }
        if (loginUser.getRolePermission() != null) {
            permissions.addAll(loginUser.getRolePermission());
        }
        return permissions;
    }

    /**
     * 取 openId：注册接口幂等，按 userId 缓存。
     *
     * @param userId   用户 id
     * @param nickname 昵称
     * @return openId
     */
    private String resolveOpenId(Long userId, String nickname) {
        String cached = openIdCache.get(userId);
        if (cached != null) {
            return cached;
        }
        OpenApiUserRegisterRequest registerRequest = new OpenApiUserRegisterRequest();
        registerRequest.setExternalId(String.valueOf(userId));
        registerRequest.setNickname(nickname);
        Result<OpenApiUserVO> result = userClient.register(registerRequest);
        if (result == null || result.getStatus() != SNAIL_AI_SUCCESS
            || result.getData() == null || !StringUtils.hasText(result.getData().getOpenId())) {
            throw new ServiceException("注册 AI 用户失败：" + (result == null ? "返回为空" : result.getMessage()));
        }
        String openId = result.getData().getOpenId();
        subscribeAgent(openId);
        openIdCache.put(userId, openId);
        return openId;
    }

    /**
     * 确保 OpenAPI 用户已订阅配置的智能体。
     * <p>
     * SnailAI 对带 openId 的对话强制校验订阅关系（SubscriptionCheckHandler），
     * 未订阅会在流开始前就拒绝对话且不返回 done。订阅接口本身幂等，重复调用无副作用。
     *
     * @param openId SnailAI 用户标识
     */
    private void subscribeAgent(String openId) {
        OpenApiUserAgentRequest request = new OpenApiUserAgentRequest();
        request.setOpenId(openId);
        request.setAgentId(properties.getAgentId());
        try {
            Result<Void> result = agentClient.subscribeAgent(request);
            if (result == null || result.getStatus() != SNAIL_AI_SUCCESS) {
                log.warn("订阅智能体失败 openId={} agentId={} msg={}", openId, properties.getAgentId(),
                    result == null ? "返回为空" : result.getMessage());
            }
        } catch (Exception e) {
            log.warn("订阅智能体异常 openId={} agentId={}", openId, properties.getAgentId(), e);
        }
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
        vo.setStatus(run.getStatus());
        vo.setReport(run.getReport());
        vo.setRound(run.getRound());
        vo.setExpireTime(run.getExpireTime());
        vo.setCreateTime(run.getCreateTime());
        return vo;
    }

    /**
     * 向 SSE 发一条事件（载荷为 JSON 对象，避免字符串默认编码把中文写坏）。
     *
     * @param emitter SSE 连接
     * @param runId   run 句柄
     * @param type    事件类型
     * @param data    事件数据
     */
    private void send(SseEmitter emitter, String runId, String type, String data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("type", type);
        payload.put("data", data);
        try {
            emitter.send(SseEmitter.event().name("message").data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * done 事件的 data 是 `{conversationId, fullText}` JSON，落库时只留最终正文，
     * 刷新回放直接把 report 当回答展示。
     *
     * @param raw 原始 done 分片
     * @return 最终正文
     */
    private String extractDoneText(String raw) {
        try {
            Dict payload = JsonUtils.parseMap(raw);
            Object fullText = payload == null ? null : payload.get("fullText");
            if (fullText != null) {
                return fullText.toString();
            }
        } catch (Exception e) {
            // 不是 JSON 就按纯文本落库
        }
        return raw;
    }

    /**
     * 取出 SnailAI 文本分片里的增量文字。
     * <p>
     * SnailAI 的 text / thinking 事件 data 是原始分片 JSON（{"content":"...","sid":"...","type":"text"}），
     * 前端只认纯文本增量；解析失败时原样返回，避免把内容吞掉。
     *
     * @param raw 原始分片 JSON
     * @return 纯文本增量
     */
    private String extractContent(String raw) {
        try {
            Dict chunk = JsonUtils.parseMap(raw);
            Object content = chunk == null ? null : chunk.get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * 发事件但吞掉失败（连接可能已断）。
     *
     * @param emitter SSE 连接
     * @param runId   run 句柄
     * @param type    事件类型
     * @param data    事件数据
     */
    private void sendQuietly(SseEmitter emitter, String runId, String type, String data) {
        try {
            send(emitter, runId, type, data);
        } catch (RuntimeException e) {
            log.debug("AI 对话流回写失败 runId={}", runId, e);
        }
    }

    /**
     * 释放对 chatStream 的订阅。
     *
     * @param holder 订阅引用
     */
    private void dispose(Disposable[] holder) {
        Disposable disposable = holder[0];
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

}