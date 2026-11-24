package org.dromara.databus.agent;

import cn.hutool.core.lang.Dict;
import com.aizuda.snail.ai.common.model.Result;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatRequest;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiChatStreamEvent;
import com.aizuda.snail.ai.common.openapi.dto.OpenApiUserAgentRequest;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiAgentClient;
import com.aizuda.snail.ai.openapi.client.core.api.OpenApiChatClient;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.DatabusAgentConversationMeta;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.mapper.DatabusAgentConversationMetaMapper;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.dromara.databus.constant.AgentConstants.EVENT_CARD;
import static org.dromara.databus.constant.AgentConstants.EVENT_FOLLOW_UPS;
import static org.dromara.databus.constant.AgentConstants.EVENT_TITLE;
import static org.dromara.databus.constant.AgentConstants.KIND_REF;
import static org.dromara.databus.constant.AgentConstants.SNAIL_AI_SUCCESS;

/**
 * run 的收尾产物生成器：会话标题 + 追问建议。
 * <p>
 * 两条产物触发点不同，所以分成两个入口，不共用一个「收尾」概念：
 * <ul>
 *   <li>{@link #generateTitle}：run 开始时就发，只吃用户原话。标题表达的是「用户想干什么」，
 *       问题本身已经足够，不必等回答；生成完立即经 SSE 推给侧栏。</li>
 *   <li>{@link #generateFollowUps}：done 之后才发，吃「问题 + 回答」。生成完推给前端并落库，
 *       推完才允许关闭连接，前端因此不需要二次请求。</li>
 * </ul>
 * 两者都走独立的内部工具智能体（9002），只吃极小上下文，不带工具、记忆和系统提示，
 * 单次成本约为主对话的 1%–2%。
 * <p>
 * 铁律：绝不阻塞主对话。任何失败（未配置、超时、解析失败、上游报错）一律静默：
 * 标题保持「未命名任务」，建议不展示。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "snail-ai.open-api", name = "enabled", havingValue = "true")
public class AgentExtrasGenerator {

    /**
     * 任务标识：只出会话标题（run 开始时）
     */
    private static final String TASK_CONVERSATION_TITLE = "[TASK:CONVERSATION_TITLE]";
    /**
     * 任务标识：只出追问建议（done 之后）
     */
    private static final String TASK_FOLLOW_UP_QUESTIONS = "[TASK:FOLLOW_UP_QUESTIONS]";
    /**
     * 任务标识：标题与建议一起出（开始时标题生成失败的兜底）
     */
    private static final String TASK_TITLE_AND_FOLLOW_UPS = "[TASK:CONVERSATION_TITLE_AND_FOLLOW_UP_QUESTIONS]";

    private static final int MAX_QUESTION_CHARS = 300;
    private static final int MAX_REPORT_CHARS = 800;
    private static final int MAX_TITLE_CHARS = 32;
    private static final int MAX_FOLLOW_UP_CHARS = 30;
    private static final int MAX_FOLLOW_UPS = 3;

    private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");
    private static final Pattern CARD_ID_HINT = Pattern.compile("[（(]cardId=[^）)]*[）)]");
    private static final Pattern BLANK = Pattern.compile("\\s+");
    private static final Pattern EDGE_PUNCT = Pattern.compile("^[\\s\"'“”‘’。.：:；;、]+|[\\s\"'“”‘’。.：:；;、]+$");

    private final OpenApiChatClient chatClient;
    private final OpenApiAgentClient agentClient;
    private final DatabusAgentProperties properties;
    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final DatabusAgentConversationMetaMapper metaMapper;
    private final AgentCardChannel cardChannel;

    /**
     * 已确认订阅过内部工具智能体的 openId（订阅接口幂等，这里只为少打一次网络）
     */
    private final Set<String> subscribedOpenIds = ConcurrentHashMap.newKeySet();

    /**
     * 异步生成会话标题并推送，方法立即返回。
     * <p>
     * 只在会话还没有标题时执行：标题一旦写库即定稿，用户改过名更不会被覆盖。
     *
     * @param run    任务（内存态）
     * @param openId SnailAI 用户标识
     */
    public void generateTitle(DatabusAgentRun run, String openId) {
        if (!available(openId)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                if (StringUtils.hasText(loadTitle(run.getConversationId()))) {
                    return;
                }
                Map<String, Object> payload = call(TASK_CONVERSATION_TITLE,
                    buildPrompt(TASK_CONVERSATION_TITLE, run.getIntent(), null), openId, run.getRunId());
                saveAndPushTitle(run, payload);
            } catch (Exception e) {
                log.warn("[databus-agent] 会话标题生成失败 runId={}", run.getRunId(), e);
            }
        });
    }

    /**
     * 异步生成追问建议并推送，推完（或超时、或未配置）才回调 {@code onFinish}。
     * <p>
     * onFinish 由调用方用于关闭 SSE：建议是这条连接上的最后一个事件，
     * 前端收到即挂到消息气泡下方，不需要再发请求。
     *
     * @param run      任务
     * @param report   本轮最终正文，可为空
     * @param openId   SnailAI 用户标识
     * @param onFinish 收尾回调，必须恰好执行一次
     */
    public void generateFollowUps(DatabusAgentRun run, String report, String openId, Runnable onFinish) {
        Runnable finish = onFinish == null ? () -> {
        } : onFinish;
        if (!available(openId)) {
            finish.run();
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                doFollowUps(run, report, openId);
            } catch (Exception e) {
                log.warn("[databus-agent] 追问建议生成失败 runId={}", run.getRunId(), e);
            } finally {
                finish.run();
            }
        });
    }

    /**
     * 判定任务 → 拼极小上下文 → 调 9002 → 解析 JSON → 落标题与建议。
     *
     * @param run    任务
     * @param report 本轮正文
     * @param openId SnailAI 用户标识
     */
    private void doFollowUps(DatabusAgentRun run, String report, String openId) {
        // 开始时标题可能没生成成功（超时/上游抖动），这里补一次；补的时候顺带把建议一起要了，省一次调用
        boolean wantTitle = !StringUtils.hasText(loadTitle(run.getConversationId()));
        // 选择卡 / 确认卡轮次只出标题：卡片才是用户此刻该处理的事，再给建议会抢注意力
        boolean wantFollowUps = properties.isFollowUpsEnabled() && !hasBlockingCard(run.getRunId());
        if (!wantTitle && !wantFollowUps) {
            return;
        }
        String task = wantTitle ? TASK_TITLE_AND_FOLLOW_UPS : TASK_FOLLOW_UP_QUESTIONS;
        Map<String, Object> payload = call(task, buildPrompt(task, run.getIntent(), report), openId, run.getRunId());
        if (payload == null) {
            return;
        }
        if (wantTitle) {
            saveAndPushTitle(run, payload);
        }
        if (!wantFollowUps) {
            return;
        }
        List<String> followUps = normalizeFollowUps(payload.get("followUps"));
        if (followUps.isEmpty()) {
            return;
        }
        // 生成期间用户可能已把这轮删掉，别给不存在的 run 留孤儿消息
        if (!appendFollowUps(run.getRunId(), followUps)) {
            return;
        }
        cardChannel.sendEvent(run.getRunId(), EVENT_FOLLOW_UPS, followUps);
    }

    /**
     * 标题落库并推送；写不进去（已有标题、并发撞唯一键）就不推送，避免把旧标题当新标题发。
     *
     * @param run     任务
     * @param payload 模型输出
     */
    private void saveAndPushTitle(DatabusAgentRun run, Map<String, Object> payload) {
        if (payload == null) {
            return;
        }
        String title = normalizeTitle(payload.get("title"));
        if (!StringUtils.hasText(title)) {
            return;
        }
        if (saveTitle(run.getConversationId(), run.getUserId(), title)) {
            cardChannel.sendEvent(run.getRunId(), EVENT_TITLE, Map.of("title", title));
        }
    }

    /**
     * 拼提示词：任务标识独占第一行，正文按「问题 / 回答」两段给，尽量短。
     *
     * @param task   任务标识
     * @param intent 用户原话（可能含卡片回执措辞）
     * @param report 助手回答，只有建议任务才需要
     * @return 提示词正文
     */
    private String buildPrompt(String task, String intent, String report) {
        StringBuilder sb = new StringBuilder(task).append('\n');
        String question = sanitize(intent);
        if (StringUtils.hasText(question)) {
            sb.append("【用户问题】").append(cut(question, MAX_QUESTION_CHARS)).append('\n');
        }
        if (StringUtils.hasText(report)) {
            sb.append("【助手回答】").append(cut(report, MAX_REPORT_CHARS)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 调内部工具智能体，返回解析后的 JSON 对象；未配置、超时、无输出、解析失败都返回 null。
     *
     * @param task    任务标识
     * @param content 提示词
     * @param openId  SnailAI 用户标识
     * @param runId   run 句柄（仅日志）
     * @return 模型输出对象，失败返回 null
     */
    private Map<String, Object> call(String task, String content, String openId, String runId) {
        Long utilityAgentId = properties.getUtilityAgentId();
        ensureSubscribed(utilityAgentId, openId);

        OpenApiChatRequest request = new OpenApiChatRequest();
        request.setAgentId(utilityAgentId);
        request.setOpenId(openId);
        // 一次性会话：内部工具不需要记忆，也不该和主对话抢同一个会话
        request.setConversationId("x-" + UUID.randomUUID().toString().replace("-", ""));
        request.setContent(content);

        String raw;
        try {
            List<OpenApiChatStreamEvent> events = chatClient.chatStream(request)
                .collectList()
                .block(Duration.ofMillis(properties.getUtilityTimeoutMs()));
            if (events == null || events.isEmpty()) {
                return null;
            }
            raw = events.stream().map(this::textOf).reduce("", String::concat);
        } catch (Exception e) {
            log.warn("[databus-agent] 内部工具调用失败 runId={} task={}", runId, task, e);
            return null;
        }
        Map<String, Object> payload = parsePayload(raw);
        if (payload == null) {
            log.debug("[databus-agent] 内部工具输出无法解析 runId={} task={} raw={}", runId, task, raw);
        }
        return payload;
    }

    /**
     * 内部工具智能体是否可用：未配置 id 或拿不到 openId 时整个功能静默关闭。
     *
     * @param openId SnailAI 用户标识
     * @return true 表示可以调用
     */
    private boolean available(String openId) {
        return properties.getUtilityAgentId() != null && StringUtils.hasText(openId);
    }

    /**
     * 去掉卡片回执的结构化措辞，避免 cardId 泄漏进标题。
     *
     * @param raw 原始文本
     * @return 可读文本
     */
    private String sanitize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String value = CARD_ID_HINT.matcher(raw).replaceAll("");
        return BLANK.matcher(value).replaceAll(" ").trim();
    }

    /**
     * 从流事件里取纯文本：text 分片取 content，done 取 fullText。
     *
     * @param event 流事件
     * @return 增量文本
     */
    private String textOf(OpenApiChatStreamEvent event) {
        String data = event.getData() == null ? "" : event.getData();
        try {
            Dict payload = JsonUtils.parseMap(data);
            if (OpenApiChatStreamEvent.TYPE_DONE.equals(event.getType())) {
                Object fullText = payload == null ? null : payload.get("fullText");
                return fullText == null ? data : String.valueOf(fullText);
            }
            Object content = payload == null ? null : payload.get("content");
            return content == null ? "" : String.valueOf(content);
        } catch (Exception e) {
            return data;
        }
    }

    /**
     * 截取第一个 JSON 对象并解析；输出夹带说明文字或代码围栏时也能兜住。
     *
     * @param raw 模型原文
     * @return 解析结果，失败返回 null
     */
    private Map<String, Object> parsePayload(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String text = CODE_FENCE.matcher(raw).replaceAll(" ");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonUtils.parseMap(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 标题清洗：去首尾标点引号、合并空白、截断。
     *
     * @param value 原始标题
     * @return 清洗后的标题，无效返回 null
     */
    private String normalizeTitle(Object value) {
        if (value == null) {
            return null;
        }
        String title = BLANK.matcher(String.valueOf(value)).replaceAll(" ").trim();
        title = EDGE_PUNCT.matcher(title).replaceAll("");
        if (!StringUtils.hasText(title)) {
            return null;
        }
        return cut(title, MAX_TITLE_CHARS);
    }

    /**
     * 建议清洗：去空、去重、限条数与长度。
     *
     * @param value 原始数组
     * @return 建议列表，可能为空
     */
    private List<String> normalizeFollowUps(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            String text = BLANK.matcher(String.valueOf(item)).replaceAll(" ").trim();
            text = EDGE_PUNCT.matcher(text).replaceAll("");
            if (StringUtils.hasText(text)) {
                unique.add(cut(text, MAX_FOLLOW_UP_CHARS));
            }
            if (unique.size() >= MAX_FOLLOW_UPS) {
                break;
            }
        }
        return new ArrayList<>(unique);
    }

    /**
     * 保存自动标题：只在会话还没有标题时写一次。
     * <p>
     * 标题是「写入即定稿」：用户改名同样写这个字段，所以非空一律不覆盖。
     *
     * @param conversationId 会话 id
     * @param userId         归属用户
     * @param title          标题
     * @return true 表示本次真的写入了
     */
    private boolean saveTitle(String conversationId, Long userId, String title) {
        try {
            DatabusAgentConversationMeta meta = metaMapper.selectOne(
                Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
                    .eq(DatabusAgentConversationMeta::getConversationId, conversationId));
            if (meta == null) {
                meta = new DatabusAgentConversationMeta();
                meta.setConversationId(conversationId);
                meta.setUserId(userId);
                meta.setPinned("0");
                meta.setDelFlag("0");
                meta.setTitle(title);
                metaMapper.insert(meta);
                return true;
            }
            if (StringUtils.hasText(meta.getTitle())) {
                return false;
            }
            metaMapper.update(null, Wrappers.<DatabusAgentConversationMeta>lambdaUpdate()
                .eq(DatabusAgentConversationMeta::getId, meta.getId())
                .and(w -> w.isNull(DatabusAgentConversationMeta::getTitle)
                    .or().eq(DatabusAgentConversationMeta::getTitle, ""))
                .set(DatabusAgentConversationMeta::getTitle, title));
            return true;
        } catch (Exception e) {
            // 并发首轮可能撞唯一键，自动标题本质是尽力而为，吞掉即可
            log.debug("[databus-agent] 自动标题写入跳过 conversationId={}", conversationId, e);
            return false;
        }
    }

    /**
     * 读取会话标题；没有 meta 行或未命名时返回 null。
     *
     * @param conversationId 会话 id
     * @return 标题，可能为空
     */
    private String loadTitle(String conversationId) {
        DatabusAgentConversationMeta meta = metaMapper.selectOne(
            Wrappers.<DatabusAgentConversationMeta>lambdaQuery()
                .eq(DatabusAgentConversationMeta::getConversationId, conversationId));
        return meta == null ? null : meta.getTitle();
    }

    /**
     * 本轮是否出现过阻塞型卡片（选择卡 / 确认卡）：这类轮次不生成建议，避免抢注意力。
     *
     * @param runId run 句柄
     * @return true 表示有阻塞卡
     */
    private boolean hasBlockingCard(String runId) {
        List<DatabusAgentMessage> cards = messageMapper.selectList(Wrappers.<DatabusAgentMessage>lambdaQuery()
            .eq(DatabusAgentMessage::getRunId, runId)
            .eq(DatabusAgentMessage::getEventCode, EVENT_CARD));
        for (DatabusAgentMessage card : cards) {
            try {
                Dict payload = JsonUtils.parseMap(card.getContent());
                Object kind = payload == null ? null : payload.get("kind");
                if (kind != null && !KIND_REF.equals(String.valueOf(kind))) {
                    return true;
                }
            } catch (Exception e) {
                // 解析不了就当阻塞卡处理，宁可少给建议
                return true;
            }
        }
        return false;
    }

    /**
     * 落一条建议消息并占用 seq（与 run 服务的 appendMessage 同一号源）。
     *
     * @param runId     run 句柄
     * @param followUps 建议列表，非空
     * @return true 表示已落库
     */
    private boolean appendFollowUps(String runId, List<String> followUps) {
        Long exists = runMapper.selectCount(Wrappers.<DatabusAgentRun>lambdaQuery()
            .eq(DatabusAgentRun::getRunId, runId)
            .eq(DatabusAgentRun::getDelFlag, "0"));
        if (exists == null || exists == 0) {
            return false;
        }
        runMapper.increaseNextSeq(runId);
        Integer nextSeq = runMapper.selectNextSeq(runId);
        DatabusAgentMessage message = new DatabusAgentMessage();
        message.setRunId(runId);
        message.setSeq(nextSeq == null ? 1 : nextSeq - 1);
        message.setEventCode(EVENT_FOLLOW_UPS);
        message.setContent(JsonUtils.toJsonString(followUps));
        messageMapper.insert(message);
        return true;
    }

    /**
     * 确保当前 OpenAPI 用户订阅了内部工具智能体（新建用户只订阅过主智能体）。
     *
     * @param agentId 内部工具智能体 id
     * @param openId  SnailAI 用户标识
     */
    private void ensureSubscribed(Long agentId, String openId) {
        if (!subscribedOpenIds.add(openId)) {
            return;
        }
        OpenApiUserAgentRequest request = new OpenApiUserAgentRequest();
        request.setOpenId(openId);
        request.setAgentId(agentId);
        try {
            Result<Void> result = agentClient.subscribeAgent(request);
            if (result == null || result.getStatus() != SNAIL_AI_SUCCESS) {
                log.warn("[databus-agent] 订阅内部工具智能体失败 openId={} agentId={}", openId, agentId);
            }
        } catch (Exception e) {
            log.warn("[databus-agent] 订阅内部工具智能体异常 openId={} agentId={}", openId, agentId, e);
        }
    }

    /**
     * 截断。
     *
     * @param text  原文
     * @param limit 上限
     * @return 截断结果
     */
    private static String cut(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit) : text;
    }

}
