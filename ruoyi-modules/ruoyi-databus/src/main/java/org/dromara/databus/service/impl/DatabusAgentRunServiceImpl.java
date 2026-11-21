package org.dromara.databus.service.impl;

import cn.hutool.core.collection.CollUtil;
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
import org.dromara.databus.agent.AgentApprovalService;
import org.dromara.databus.agent.AgentCard;
import org.dromara.databus.agent.AgentCardChannel;
import org.dromara.databus.agent.AgentStepRecorder;
import org.dromara.databus.agent.AgentSystemEventCatalog;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.DatabusAgentConversationMeta;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.DatabusAgentStep;
import org.dromara.databus.domain.bo.AgentCardReplyBo;
import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.bo.AgentSystemEventBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentApprovalModeVo;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentConversationMetaMapper;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.dromara.databus.constant.AgentConstants.ACTION_CANCEL;
import static org.dromara.databus.constant.AgentConstants.CONVERSATION_HISTORY_LIMIT;
import static org.dromara.databus.constant.AgentConstants.DEFAULT_CONVERSATION_LIMIT;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD;
import static org.dromara.databus.constant.AgentConstants.EVENT_CARD_REPLY;
import static org.dromara.databus.constant.AgentConstants.EVENT_RUN_LIFECYCLE_ENDED;
import static org.dromara.databus.constant.AgentConstants.EVENT_SYSTEM_EVENT;
import static org.dromara.databus.constant.AgentConstants.MAX_CARD_FREE_TEXT;
import static org.dromara.databus.constant.AgentConstants.MAX_CONVERSATION_LIMIT;
import static org.dromara.databus.constant.AgentConstants.OPERATION_CREATE;
import static org.dromara.databus.constant.AgentConstants.PREVIOUS_SUMMARY_LIMIT;
import static org.dromara.databus.constant.AgentConstants.SNAIL_AI_SUCCESS;
import static org.dromara.databus.constant.AgentConstants.STEP_DIGEST_LIMIT;
import static org.dromara.databus.constant.AgentConstants.TITLE_LIMIT;
import static org.dromara.databus.constant.CommonConstants.OWNER_SYSTEM;
import static org.dromara.databus.constant.CommonConstants.STATUS_DRAFT;

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
     * 标题清洗用：整段代码块、行首 markdown 记号、行内反引号/星号、连续空白。
     * <p>
     * 刻意不动下划线——用户问句里常有 {@code chain_draft_save}、{@code field_mapping} 这类标识符。
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
     * 摘要脱敏：token / password / secret / api-key / authorization 的值一律打码。
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
        "(?i)(\"?(?:token|password|passwd|secret|api[-_]?key|authorization)\"?\\s*[:=]\\s*\"?)([^\",}\\s]+)");

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final DatabusAgentStepMapper stepMapper;
    private final DatabusAgentConversationMetaMapper metaMapper;
    private final IDatabusAgentSnapshotService snapshotService;
    private final IDatabusChainService chainService;
    private final AgentStepRecorder stepRecorder;
    private final OpenApiChatClient chatClient;
    private final OpenApiUserClient userClient;
    private final OpenApiAgentClient agentClient;
    private final DatabusAgentProperties properties;
    private final AgentApprovalService approvalService;
    private final AgentCardChannel cardChannel;
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
        // 只带附件（选中一段日志直接问）或只带系统事件也是合法消息，三者全空才拦
        if (!StringUtils.hasText(bo.getContent()) && CollUtil.isEmpty(bo.getAttachments())
            && bo.getSystemEvent() == null) {
            throw new ServiceException("内容不能为空");
        }
        String conversationId = resolveConversationId(bo.getConversationId());
        // 会话已软删就拒绝继续写 run：否则新 run 会被列表的 del_flag 过滤掉，用户看到 AI 回了却查不到会话
        if (StringUtils.hasText(bo.getConversationId()) && isConversationDeleted(conversationId)) {
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
            appendMessage(run.getRunId(), EVENT_CARD_REPLY, null,
                JsonUtils.toJsonString(cardReply.reply()));
        }
        // 系统事件只进模型上下文：用户气泡用的是 buildIntent 产出的展示文案，不含任何指令
        if (bo.getSystemEvent() != null) {
            appendMessage(run.getRunId(), EVENT_SYSTEM_EVENT, null,
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
        run.setPermissions(JsonUtils.toJsonString(snapshotPermissions(loginUser)));
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
        DatabusAgentRun run = requireOwnedRun(runId);
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
            dispose(subscription);
        });
        emitter.onTimeout(() -> {
            cardChannel.unregister(runId, emitter);
            dispose(subscription);
            settle(settled, run, AgentRunStatusEnum.STOPPED, null);
            emitter.complete();
        });
        emitter.onError(e -> {
            cardChannel.unregister(runId, emitter);
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
            // 跨 run 续接：本轮附件 + 原话 + 上一轮工具摘要（含 nextCursor 等关键词），否则「继续加载下一页」拿不到游标
            String content = buildChatContent(run);
            request.setContent(content);
            // 装配规则会演进，把本次实际发送的内容留一份快照，事后才能回答「模型当时看到了什么」
            runMapper.update(null, Wrappers.<DatabusAgentRun>lambdaUpdate()
                .eq(DatabusAgentRun::getRunId, run.getRunId())
                .set(DatabusAgentRun::getResolvedPrompt, content));
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
        // 已删会话不再对外暴露运行态，避免刷新后被恢复成一条会话
        if (isConversationDeleted(conversationId)) {
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
        // 历史回放也认软删：否则刷新时前端会按历史重建本地桶，已删会话又冒出来
        if (isConversationDeleted(conversationId)) {
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
        fillCards(vos);
        return vos;
    }

    /**
     * 一次批量补齐各 run 的卡片与回执：刷新后前端据此还原（只还原，不改卡片本身）。
     *
     * @param runs 会话内 run 列表
     */
    private void fillCards(List<AgentRunVo> runs) {
        List<String> runIds = runs.stream().map(AgentRunVo::getRunId).toList();
        List<DatabusAgentMessage> messages = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .in(DatabusAgentMessage::getRunId, runIds)
            .in(DatabusAgentMessage::getEventCode, EVENT_CARD, EVENT_CARD_REPLY)
            .orderByAsc(DatabusAgentMessage::getSeq));
        Map<String, List<String>> cardsByRun = new LinkedHashMap<>();
        Map<String, List<String>> repliesByRun = new LinkedHashMap<>();
        for (DatabusAgentMessage message : messages) {
            boolean isCard = EVENT_CARD.equals(message.getEventCode());
            Map<String, List<String>> target = isCard ? cardsByRun : repliesByRun;
            target.computeIfAbsent(message.getRunId(), key -> new ArrayList<>()).add(message.getContent());
        }
        for (AgentRunVo vo : runs) {
            vo.setCards(cardsByRun.getOrDefault(vo.getRunId(), List.of()));
            vo.setCardReplies(repliesByRun.getOrDefault(vo.getRunId(), List.of()));
        }
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
        conversations.forEach(vo -> vo.setTitle(cleanTitle(vo.getTitle())));
        return conversations;
    }

    @Override
    public void renameConversation(String conversationId, String title) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setCustomTitle(title.trim());
        saveMeta(meta);
    }

    @Override
    public void pinConversation(String conversationId, boolean pinned) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setPinned(pinned ? "1" : "0");
        saveMeta(meta);
    }

    @Override
    public void deleteConversation(String conversationId) {
        Long userId = requireOwnedConversation(conversationId);
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setDelFlag("1");
        saveMeta(meta);
    }

    @Override
    public void restoreConversation(String conversationId) {
        Long userId = requireOwnedConversation(conversationId);
        // 撤销删除只复位软删标记：run / step / message 一直没动，复位后列表自然回来
        DatabusAgentConversationMeta meta = loadOrInitMeta(conversationId, userId);
        meta.setDelFlag("0");
        saveMeta(meta);
    }

    /**
     * 取会话的覆盖信息行，没有就构造一行待插入的（pinned / del_flag 给默认值）。
     * <p>
     * 不过滤 del_flag：软删过的会话再被操作时要找回同一行改写，否则会撞 conversation_id 唯一键。
     *
     * @param conversationId 会话 id
     * @param userId         归属用户
     * @return 已存在或待插入的 meta 行
     */
    private DatabusAgentConversationMeta loadOrInitMeta(String conversationId, Long userId) {
        DatabusAgentConversationMeta meta = metaMapper.selectOne(
            Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
                .eq(DatabusAgentConversationMeta::getConversationId, conversationId));
        if (meta != null) {
            return meta;
        }
        meta = new DatabusAgentConversationMeta();
        meta.setConversationId(conversationId);
        meta.setUserId(userId);
        meta.setPinned("0");
        meta.setDelFlag("0");
        return meta;
    }

    /**
     * 落 meta：无主键插入，有主键按非空字段更新（null 字段不覆盖）。
     *
     * @param meta meta 行
     */
    private void saveMeta(DatabusAgentConversationMeta meta) {
        if (meta.getId() == null) {
            metaMapper.insert(meta);
        } else {
            metaMapper.updateById(meta);
        }
    }

    /**
     * 会话是否已被软删：meta 行不存在视为未删（从未改过标题/置顶/删除的会话没有 meta 行）。
     *
     * @param conversationId 会话 id
     * @return true 表示已软删，所有读接口都不该再返回它
     */
    private boolean isConversationDeleted(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        Long count = metaMapper.selectCount(Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
            .eq(DatabusAgentConversationMeta::getConversationId, conversationId)
            .eq(DatabusAgentConversationMeta::getDelFlag, "1"));
        return count != null && count > 0;
    }

    /**
     * 校验会话归属当前用户（会话由 run 聚合而成，有 run 才算存在），返回当前用户 id。
     *
     * @param conversationId 会话 id
     * @return 当前用户 id
     */
    private Long requireOwnedConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new ServiceException("会话不存在");
        }
        Long userId = LoginHelper.getUserId();
        if (userId == null) {
            throw new ServiceException("当前登录用户为空");
        }
        Long count = runMapper.selectCount(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getUserId, userId)
            .eq(DatabusAgentRun::getConversationId, conversationId));
        if (count == null || count == 0) {
            throw new ServiceException("无权访问该会话");
        }
        return userId;
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRun(String runId) {
        DatabusAgentRun run = requireOwnedRun(runId);
        ensureSettled(run, "删除");
        // 逻辑删除即可：step / message 保留，但历史回放与上下文续接都不再看到这一轮
        runMapper.deleteById(run.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentChatStartVo retryRun(String runId) {
        DatabusAgentRun old = requireOwnedRun(runId);
        ensureSettled(old, "重试");
        LoginUser loginUser = LoginHelper.getLoginUser();
        if (loginUser == null) {
            throw new ServiceException("当前登录用户为空");
        }
        if (properties.getAgentId() == null) {
            throw new ServiceException("AI 助手未配置 databus.agent.agent-id");
        }
        // 只允许最后一轮：新 run 的 create_time 排在末尾，重试中间轮会让历史顺序错位
        List<DatabusAgentRun> runs = listConversationRuns(old.getUserId(), old.getConversationId());
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
            appendMessage(run.getRunId(), event.getEventCode(), null, event.getContent());
        }
        return new AgentChatStartVo(run.getRunId(), run.getConversationId(), run.getIntent());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<AgentRollbackVo> revertRun(String runId) {
        DatabusAgentRun run = requireOwnedRun(runId);
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
    private List<DatabusAgentRun> listConversationRuns(Long userId, String conversationId) {
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
    private void ensureSettled(DatabusAgentRun run, String action) {
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
    private String buildChatContent(DatabusAgentRun run) {
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
                appendMessage(run.getRunId(), EVENT_RUN_LIFECYCLE_ENDED, lastChainId, report);
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
        vo.setAttachments(run.getAttachments());
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

    @Override
    public AgentApprovalModeVo getApprovalMode(String conversationId) {
        AgentApprovalModeVo vo = new AgentApprovalModeVo();
        vo.setMode(approvalService.getMode(conversationId, LoginHelper.getUserId()).getCode());
        vo.setAllowFullAccess(approvalService.isAllowFullAccess());
        return vo;
    }

    @Override
    public void saveApprovalMode(String conversationId, String mode) {
        approvalService.saveMode(conversationId, LoginHelper.getUserId(), mode);
    }

}