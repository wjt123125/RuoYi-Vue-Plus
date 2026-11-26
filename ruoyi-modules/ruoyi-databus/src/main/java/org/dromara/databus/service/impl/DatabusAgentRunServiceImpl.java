package org.dromara.databus.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatStreamEvent;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.agent.event.AgentCard;
import org.dromara.databus.agent.event.AgentCardChannel;
import org.dromara.databus.agent.support.AgentConversationMetaSupport;
import org.dromara.databus.agent.event.AgentError;
import org.dromara.databus.agent.support.AgentErrorResolver;
import org.dromara.databus.agent.run.AgentTitleFollowUpGenerator;
import org.dromara.databus.agent.support.AgentIdentityResolver;
import org.dromara.databus.agent.run.AgentRunReplayAssembler;
import org.dromara.databus.agent.run.AgentRunRollbackSupport;
import org.dromara.databus.agent.run.AgentRunStreamSink;
import org.dromara.databus.agent.run.AgentStepRecorder;
import org.dromara.databus.agent.event.AgentSystemEventCatalog;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.bo.AgentCardReplyBo;
import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentApprovalModeVo;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.service.IDatabusAgentRunService;
import org.dromara.system.api.model.LoginUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.dromara.databus.constant.AgentConstants.ACTION_CANCEL;
import static org.dromara.databus.constant.AgentConstants.DEFAULT_CONVERSATION_LIMIT;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD_REPLY;
import static org.dromara.databus.constant.AgentConstants.EVENT_SYSTEM_EVENT;
import static org.dromara.databus.constant.AgentConstants.MAX_CARD_FREE_TEXT;
import static org.dromara.databus.constant.AgentConstants.MAX_CONVERSATION_LIMIT;

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

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final AgentStepRecorder stepRecorder;
    private final OpenApiChatClient chatClient;
    private final DatabusAgentProperties properties;
    private final AgentCardChannel cardChannel;
    private final AgentErrorResolver agentErrorResolver;
    private final AgentTitleFollowUpGenerator extrasGenerator;
    private final AgentRunStreamSink streamSink;
    private final AgentRunReplayAssembler replayAssembler;
    private final AgentConversationMetaSupport conversationSupport;
    private final AgentRunRollbackSupport rollbackSupport;
    private final AgentIdentityResolver identityResolver;

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
        // 只带附件（选中一段日志直接问）或只带系统事件也是合法消息，三者全空才拦
        if (!StringUtils.hasText(bo.getContent()) && CollUtil.isEmpty(bo.getAttachments())
            && bo.getSystemEvent() == null) {
            throw new ServiceException("内容不能为空");
        }
        String conversationId = identityResolver.resolveConversationId(bo.getConversationId());
        // 会话已软删就拒绝继续写 run：否则新 run 会被列表的 del_flag 过滤掉，用户看到 AI 回了却查不到会话
        if (StringUtils.hasText(bo.getConversationId()) && conversationSupport.isConversationDeleted(conversationId)) {
            throw new ServiceException("会话已删除，请新建会话");
        }
        supersedeActiveRuns(userId, conversationId);

        String attachments = CollUtil.isEmpty(bo.getAttachments())
            ? null : JsonUtils.toJsonString(bo.getAttachments());
        // 回执先过白名单与幂等校验，再进 intent：重复回执直接忽略，非法 action 直接拒绝
        ResolvedCardReply cardReply = bo.getCardReply() == null
            ? null : resolveCardReply(userId, conversationId, bo.getCardReply());
        DatabusAgentRun run = createRun(userId, loginUser, conversationId, buildIntent(bo, cardReply), attachments);
        // 卡片回执落库：工具层据此判定 userConfirmed，模型自报的 true 不单独作数（运行时 §16）
        if (cardReply != null) {
            streamSink.appendMessage(run.getRunId(), EVENT_CARD_REPLY, null,
                JsonUtils.toJsonString(cardReply.reply()));
        }
        // 系统事件只进模型上下文：用户气泡用的是 buildIntent 产出的展示文案，不含任何指令
        if (bo.getSystemEvent() != null) {
            streamSink.appendMessage(run.getRunId(), EVENT_SYSTEM_EVENT, null,
                JsonUtils.toJsonString(bo.getSystemEvent()));
        }
        return new AgentChatStartVo(run.getRunId(), conversationId, run.getIntent(),
            cardReply == null ? null : cardReply.display());
    }

    /**
     * 建一个进行中的 run 并落库。
     *
     * @param userId         发起人
     * @param loginUser      登录用户（权限点快照来源）
     * @param conversationId 会话 id
     * @param intent         本轮意图
     * @param attachments    上下文附件 JSON，可为空
     * @return 已落库的 run
     */
    private DatabusAgentRun createRun(Long userId, LoginUser loginUser, String conversationId, String intent,
                                      String attachments) {
        DatabusAgentRun run = new DatabusAgentRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setUserId(userId);
        run.setIntent(intent);
        run.setConversationId(conversationId);
        run.setAttachments(attachments);
        run.setRound(0);
        run.setStatus(AgentRunStatusEnum.RUNNING.getCode());
        run.setSideEffectApproved("0");
        run.setPermissions(JsonUtils.toJsonString(identityResolver.snapshotPermissions(loginUser)));
        run.setExpireTime(LocalDateTime.now().plusMinutes(properties.getRunTtlMinutes()));
        run.setNextSeq(1);
        // 红线 3：创建人显式落本人，不依赖填充器
        run.setCreateBy(userId);
        runMapper.insert(run);
        return run;
    }

    /**
     * 组装本轮意图：系统事件取目录里的展示文案，卡片回执换成给模型的结构化说明。
     * <p>
     * 回执轮次这里只放 hint（带 cardId，说明用户点了什么），不拼用户可见文案；
     * 气泡展示读 {@link ResolvedCardReply#display()}，两者不能混用。
     *
     * @param bo        对话入参
     * @param cardReply 已校验的卡片回执，重复回执为 null
     * @return 意图文本，原样进模型上下文
     */
    private String buildIntent(AgentChatBo bo, ResolvedCardReply cardReply) {
        // 系统事件优先：前端只报事实，文案由后端目录生成，历史回放同样是干净的一句
        if (bo.getSystemEvent() != null) {
            return AgentSystemEventCatalog
                .resolve(bo.getSystemEvent().getCode(), bo.getSystemEvent().getPayload())
                .display();
        }
        // 只带附件时没有原话，intent 落空串而不是 null，避免后续拼接出现 "null"
        String content = bo.getContent() == null ? "" : bo.getContent();
        if (cardReply == null) {
            return content;
        }
        // 回执轮次的正文就是选中项的文案，再拼一遍只会让模型看到重复的两句话
        return cardReply.hint();
    }

    /**
     * 校验卡片回执：白名单 + 单选/多选规则 + 自由输入规则 + 同卡幂等。
     * <p>
     * 回执落在「点选后新建的 run」上，所以按会话内的卡片消息反查原始卡片；
     * 同一张卡已有回执时直接返回 null（幂等忽略，不报错、不重复落库）。
     *
     * @param userId         发起人
     * @param conversationId 会话 id
     * @param raw            前端回传的原始回执
     * @return 规范化回执；null = 重复回执，直接忽略
     */
    private ResolvedCardReply resolveCardReply(Long userId, String conversationId, AgentCardReplyBo raw) {
        String cardId = raw.getCardId() == null ? "" : raw.getCardId().trim();
        if (cardId.isEmpty()) {
            throw new ServiceException("cardId 不能为空");
        }
        List<DatabusAgentRun> runs = runMapper.selectList(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId)
            .select(DatabusAgentRun::getRunId));
        if (runs.isEmpty()) {
            throw new ServiceException("卡片不存在或已失效：" + cardId);
        }
        List<String> runIds = runs.stream().map(DatabusAgentRun::getRunId).toList();
        List<DatabusAgentMessage> messages = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .in(DatabusAgentMessage::getRunId, runIds)
            .in(DatabusAgentMessage::getEventCode, EVENT_CARD, EVENT_CARD_REPLY)
            .orderByDesc(DatabusAgentMessage::getSeq));

        AgentCard card = null;
        for (DatabusAgentMessage message : messages) {
            if (EVENT_CARD_REPLY.equals(message.getEventCode())) {
                AgentCardReplyBo old = JsonUtils.parseObject(message.getContent(), AgentCardReplyBo.class);
                if (old != null && cardId.equals(old.getCardId())) {
                    // 同一张卡已有回执：幂等忽略，避免重复点选把同一轮再跑一遍
                    return null;
                }
                continue;
            }
            if (card == null) {
                AgentCard parsed = JsonUtils.parseObject(message.getContent(), AgentCard.class);
                if (parsed != null && cardId.equals(parsed.cardId())) {
                    card = parsed;
                }
            }
        }
        if (card == null) {
            throw new ServiceException("卡片不存在或已失效：" + cardId);
        }

        // 白名单与开关只在 payload 里取：确认卡没有自由输入，资源卡没有选项
        List<AgentCard.Option> cardOptions;
        boolean multiSelect = false;
        boolean allowFreeText = false;
        if (card.payload() instanceof AgentCard.ConfirmPayload confirm) {
            cardOptions = confirm.options();
        } else if (card.payload() instanceof AgentCard.ChoicePayload choice) {
            cardOptions = choice.options();
            multiSelect = Boolean.TRUE.equals(choice.multiSelect());
            allowFreeText = Boolean.TRUE.equals(choice.allowFreeText());
        } else {
            cardOptions = List.of();
        }
        Map<String, String> keyToLabel = new LinkedHashMap<>();
        if (cardOptions != null) {
            for (AgentCard.Option option : cardOptions) {
                if (option != null && StringUtils.hasText(option.key())) {
                    keyToLabel.putIfAbsent(option.key(), option.label());
                }
            }
        }
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (raw.getActions() != null) {
            for (String action : raw.getActions()) {
                if (StringUtils.hasText(action)) {
                    actions.add(action.trim());
                }
            }
        }
        // 兼容旧回执：只有 action 没有 actions 时按单选项处理
        if (StringUtils.hasText(raw.getAction())) {
            actions.add(raw.getAction().trim());
        }
        // 取消是协议保留动作：不对应任何选项，跳过白名单与「至少选一项」校验
        boolean cancelled = actions.size() == 1 && actions.contains(ACTION_CANCEL);
        String text = cancelled ? null : (StringUtils.hasText(raw.getText()) ? raw.getText().trim() : null);
        if (!cancelled) {
            for (String action : actions) {
                if (!keyToLabel.containsKey(action)) {
                    throw new ServiceException("卡片选项不存在：" + action);
                }
            }
            if (!multiSelect && actions.size() > 1) {
                throw new ServiceException("该卡片为单选，最多只能选 1 项");
            }
            if (text != null && !allowFreeText) {
                throw new ServiceException("该卡片不支持自由输入");
            }
            if (text != null && text.length() > MAX_CARD_FREE_TEXT) {
                throw new ServiceException("自由输入内容不能超过 " + MAX_CARD_FREE_TEXT + " 个字符");
            }
            if (actions.isEmpty() && text == null) {
                throw new ServiceException("请至少选择一个选项或填写内容");
            }
        }

        StringBuilder hint = new StringBuilder("【卡片回执】");
        StringBuilder display = new StringBuilder();
        if (cancelled) {
            // 对外文案与说明页一样短，不带 cardId
            display.append("取消这次选择");
            hint.append("用户取消了这张卡片，没有选定任何一项（cardId=").append(cardId)
                .append("）。不要替用户做决定，等用户下一步指示。");
        } else if (actions.isEmpty()) {
            display.append(text == null ? "" : text);
            hint.append("用户未选择选项");
        } else {
            List<String> labels = actions.stream()
                .map(key -> keyToLabel.getOrDefault(key, key))
                .toList();
            // 单选就是那一个选项文案，多选按「已选「A、B」」，自由输入用分号接在后面
            if (labels.size() == 1) {
                display.append(labels.get(0));
            } else {
                display.append("已选「").append(String.join("、", labels)).append("」");
            }
            if (text != null) {
                display.append("；").append(text);
            }
            hint.append("用户已在卡片上选择 ").append(String.join("、", labels));
        }
        if (!cancelled) {
            hint.append("（cardId=").append(cardId).append("）");
            if (text != null) {
                hint.append("；补充说明：").append(text);
            }
            hint.append("。");
        }

        AgentCardReplyBo normalized = new AgentCardReplyBo();
        normalized.setCardId(cardId);
        normalized.setActions(new ArrayList<>(actions));
        normalized.setText(text);
        // 展示文案随回执一起落库：刷新回放直接读这句，前端不用再按选项反推
        normalized.setDisplay(display.toString());
        return new ResolvedCardReply(normalized, hint.toString(), display.toString());
    }

    /**
     * 规范化后的卡片回执。
     *
     * @param reply   落库回执（含用户可见的 display）
     * @param hint    给模型看的说明，含 cardId，不能上屏
     * @param display 给用户看的文案，进数据库与气泡
     */
    private record ResolvedCardReply(AgentCardReplyBo reply, String hint, String display) {
    }

    @Override
    public SseEmitter openStream(String runId) {
        DatabusAgentRun run = replayAssembler.requireOwnedRun(runId);
        if (!AgentRunStatusEnum.RUNNING.getCode().equals(run.getStatus())) {
            throw new ServiceException("任务已结束，无法继续对话");
        }
        SseEmitter emitter = new SseEmitter(properties.getStreamTimeoutMs());
        AtomicBoolean settled = new AtomicBoolean(false);
        AtomicBoolean clientGone = new AtomicBoolean(false);
        // 卡片要与模型文字同序，所以按 runId 登记本次连接，供工具执行线程回写（运行时 §16）
        cardChannel.register(runId, emitter);

        Disposable[] subscription = new Disposable[1];
        emitter.onCompletion(() -> {
            cardChannel.unregister(runId, emitter);
            streamSink.dispose(subscription);
        });
        emitter.onTimeout(() -> {
            cardChannel.unregister(runId, emitter);
            streamSink.dispose(subscription);
            streamSink.settle(settled, run, AgentRunStatusEnum.STOPPED, null);
            emitter.complete();
        });
        emitter.onError(e -> {
            cardChannel.unregister(runId, emitter);
            clientGone.set(true);
            streamSink.settle(settled, run, AgentRunStatusEnum.STOPPED, null);
        });

        // SnailAI 不可达也要走 SSE 的 error 事件，不能抛异常让浏览器只看到「连接已断开」
        OpenApiChatRequest request = new OpenApiChatRequest();
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            request.setAgentId(properties.getAgentId());
            request.setOpenId(identityResolver.resolveOpenId(run.getUserId(),
                loginUser == null ? null : loginUser.getNickname()));
            request.setConversationId(run.getConversationId());
            // 跨 run 续接：本轮附件 + 原话 + 上一轮工具摘要（含 nextCursor 等关键词），否则「继续加载下一页」拿不到游标
            String content = replayAssembler.buildChatContent(run);
            request.setContent(content);
            // 装配规则会演进，把本次实际发送的内容留一份快照，事后才能回答「模型当时看到了什么」
            runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
                .eq(DatabusAgentRun::getRunId, run.getRunId())
                .set(DatabusAgentRun::getResolvedPrompt, content));
        } catch (Exception e) {
            log.warn("准备 AI 对话失败 runId={}", runId, e);
            AgentError agentError = agentErrorResolver.fromException(runId, e);
            if (streamSink.settle(settled, run, AgentRunStatusEnum.FAILED, null)) {
                streamSink.persistRunError(runId, agentError);
                streamSink.sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                    JsonUtils.toJsonString(agentError));
            }
            emitter.complete();
            return emitter;
        }

        try {
            // 标题不等回答：拿到用户原话就能概括，生成完立即推给前端，侧栏从「未命名任务」变成真标题
            extrasGenerator.generateTitle(run, request.getOpenId());
            subscription[0] = chatClient.chatStream(request).subscribe(
                event -> {
                    if (settled.get()) {
                        return;
                    }
                    String type = event.getType();
                    String data = event.getData() == null ? "" : event.getData();
                    if (OpenApiChatStreamEvent.TYPE_TEXT.equals(type) || OpenApiChatStreamEvent.TYPE_THINKING.equals(type)) {
                        data = streamSink.extractContent(data);
                    }
                    // 上游错误：原始内容只进日志，用户侧只下发统一错误信封
                    if (OpenApiChatStreamEvent.TYPE_ERROR.equals(type)) {
                        AgentError agentError = agentErrorResolver.fromUpstream(runId, data);
                        if (streamSink.settle(settled, run, AgentRunStatusEnum.FAILED, null)) {
                            streamSink.persistRunError(runId, agentError);
                            streamSink.sendQuietly(emitter, runId, type, JsonUtils.toJsonString(agentError));
                        }
                        emitter.complete();
                        return;
                    }
                    streamSink.send(emitter, runId, type, data);
                    if (OpenApiChatStreamEvent.TYPE_DONE.equals(type)) {
                        String report = streamSink.extractDoneText(data);
                        if (streamSink.settle(settled, run, AgentRunStatusEnum.FINISHED, report)) {
                            // 建议必须等回答稳定才有意义，所以只能在 done 之后生成；
                            // 生成完把建议推给前端再关连接，前端无需再做二次请求
                            extrasGenerator.generateFollowUps(run, report, request.getOpenId(), emitter::complete);
                        } else {
                            emitter.complete();
                        }
                    }
                },
                error -> {
                    // 只有 emitter 自己报错才是浏览器断开；上游把流掐断也常以 IOException 出现，不能一并当作客户端消失
                    boolean byClient = clientGone.get();
                    if (byClient) {
                        streamSink.settle(settled, run, AgentRunStatusEnum.STOPPED, null);
                    } else {
                        AgentError agentError = agentErrorResolver.fromException(runId, error);
                        if (streamSink.settle(settled, run, AgentRunStatusEnum.FAILED, null)) {
                            streamSink.persistRunError(runId, agentError);
                            streamSink.sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                                JsonUtils.toJsonString(agentError));
                        }
                    }
                    emitter.complete();
                },
                () -> {
                    if (settled.get()) {
                        return;
                    }
                    // 上游没发 done 就直接结束（如拒绝对话），不能静默收尾，否则浏览器只能看到「连接已断开」
                    log.warn("AI 对话流未收到 done 即结束 runId={}", runId);
                    AgentError agentError = agentErrorResolver.unknown(runId, "upstream completed without done");
                    if (streamSink.settle(settled, run, AgentRunStatusEnum.FAILED, null)) {
                        streamSink.persistRunError(runId, agentError);
                        streamSink.sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                            JsonUtils.toJsonString(agentError));
                    }
                    emitter.complete();
                }
            );
        } catch (Exception e) {
            log.warn("发起 AI 对话失败 runId={}", runId, e);
            AgentError agentError = agentErrorResolver.fromException(runId, e);
            if (streamSink.settle(settled, run, AgentRunStatusEnum.FAILED, null)) {
                streamSink.persistRunError(runId, agentError);
                streamSink.sendQuietly(emitter, runId, OpenApiChatStreamEvent.TYPE_ERROR,
                    JsonUtils.toJsonString(agentError));
            }
            emitter.complete();
        }
        return emitter;
    }

    @Override
    public void stop(String runId) {
        replayAssembler.requireOwnedRun(runId);
        LambdaUpdateWrapper<DatabusAgentRun> wrapper = Wrappers.<DatabusAgentRun>lambdaUpdate()
            .eq(DatabusAgentRun::getRunId, runId)
            .eq(DatabusAgentRun::getStatus, AgentRunStatusEnum.RUNNING.getCode())
            .set(DatabusAgentRun::getStatus, AgentRunStatusEnum.STOPPED.getCode())
            .set(DatabusAgentRun::getUpdateTime, LocalDateTime.now());
        runMapper.update(null, wrapper);
    }

    @Override
    public void reportCanvasSkip(String runId, String chainName) {
        replayAssembler.requireOwnedRun(runId);
        String name = StringUtils.hasText(chainName) ? chainName : "当前链路";
        // 借审计 step 告知模型：本轮画布更新被人丢弃，下一轮不要再重复推同一份改动
        stepRecorder.recordReject(runId, "canvas_skip",
            JsonUtils.toJsonString(Map.of("chainName", name)),
            JsonUtils.toJsonString(Map.of("message", "用户丢弃了本次画布更新，画布上是用户未保存的本地改动")),
            "用户丢弃画布更新", 0);
    }

    @Override
    public AgentRunVo getActiveRun(String conversationId) {
        return replayAssembler.getActiveRun(conversationId);
    }

    @Override
    public List<AgentMessageVo> listMessages(String runId) {
        return replayAssembler.listMessages(runId);
    }

    @Override
    public List<AgentRunVo> listConversationRuns(String conversationId) {
        return replayAssembler.listConversationRuns(conversationId);
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
        return conversations;
    }

    @Override
    public List<AgentConversationVo> searchConversations(String keyword, Integer limit) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new ServiceException("当前登录用户为空");
        }
        int size = limit == null || limit <= 0
            ? DEFAULT_CONVERSATION_LIMIT
            : Math.min(limit, MAX_CONVERSATION_LIMIT);
        List<AgentConversationVo> conversations = runMapper.searchConversations(userId, keyword.trim(), size);
        return conversations;
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        conversationSupport.renameConversation(conversationId, title);
    }

    @Override
    public void pinConversation(String conversationId, boolean pinned) {
        conversationSupport.pinConversation(conversationId, pinned);
    }

    @Override
    public void deleteConversation(String conversationId) {
        conversationSupport.deleteConversation(conversationId);
    }

    @Override
    public void restoreConversation(String conversationId) {
        conversationSupport.restoreConversation(conversationId);
    }

    @Override
    public List<AgentRollbackVo> rollbackRun(String runId) {
        return rollbackSupport.rollbackRun(runId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRun(String runId) {
        rollbackSupport.deleteRun(runId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentChatStartVo retryRun(String runId) {
        DatabusAgentRun old = replayAssembler.requireOwnedRun(runId);
        rollbackSupport.ensureSettled(old, "重试");
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            throw new ServiceException("当前登录用户为空");
        }
        if (properties.getAgentId() == null) {
            throw new ServiceException("AI 助手未配置 databus.agent.agent-id");
        }
        // 只允许最后一轮：新 run 的 create_time 排在末尾，重试中间轮会让历史顺序错位
        List<DatabusAgentRun> runs = rollbackSupport.listConversationRuns(old.getUserId(), old.getConversationId());
        for (int i = 0; i < runs.size(); i++) {
            if (runId.equals(runs.get(i).getRunId()) && i < runs.size() - 1) {
                throw new ServiceException("只能重试最后一轮回复");
            }
        }
        // 旧轮次逻辑删除，新轮次沿用原意图与同一批附件重新发起：前端只替换这一条回复，不新增用户气泡
        // 系统事件不在 intent 里（指令只走模型上下文）、卡片回执是 userConfirmed 的唯一凭据，
        // 两者都必须显式搬到新 run，否则重试会丢掉事件事实或审批凭据
        List<DatabusAgentMessage> carried = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .eq(DatabusAgentMessage::getRunId, runId)
            .in(DatabusAgentMessage::getEventCode, EVENT_SYSTEM_EVENT, EVENT_CARD_REPLY)
            .orderByAsc(DatabusAgentMessage::getSeq));
        runMapper.deleteById(old.getId());
        supersedeActiveRuns(old.getUserId(), old.getConversationId());
        DatabusAgentRun run = createRun(old.getUserId(), loginUser, old.getConversationId(), old.getIntent(),
            old.getAttachments());
        for (DatabusAgentMessage event : carried) {
            streamSink.appendMessage(run.getRunId(), event.getEventCode(), null, event.getContent());
        }
        return new AgentChatStartVo(run.getRunId(), run.getConversationId(), run.getIntent());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentRollbackVo> revertRun(String runId) {
        return rollbackSupport.revertRun(runId);
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

    @Override
    public AgentApprovalModeVo getApprovalMode(String conversationId) {
        return conversationSupport.getApprovalMode(conversationId);
    }

    @Override
    public void saveApprovalMode(String conversationId, String mode) {
        conversationSupport.saveApprovalMode(conversationId, mode);
    }

}