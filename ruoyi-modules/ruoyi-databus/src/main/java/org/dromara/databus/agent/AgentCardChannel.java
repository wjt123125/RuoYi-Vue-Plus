package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.enums.AgentStepStatusEnum;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.dromara.databus.constant.AgentConstants.EVENT_CARD;
import static org.dromara.databus.constant.AgentConstants.EVENT_STEP_END;
import static org.dromara.databus.constant.AgentConstants.EVENT_STEP_START;

/**
 * 对话 SSE 的卡片 / 工具阶段回写通道（运行时 §16）。
 * <p>
 * 卡片必须与模型文字同序，所以不走全局推送，而是回写到本次 run 的 SSE 连接。
 * 工具执行线程拿不到 emitter，靠 runId 注册表反查；只新增通道，不改 run.next_seq 号源。
 * <p>
 * 卡片同时落一行 {@code databus_agent_message}（{@code event_code=card}）：
 * 连接恰好未建立 / 用户刷新页面时，靠它把卡片还原回历史，不能只活在内存里。
 * 工具阶段只推 SSE、不落库：它是会话里的瞬时状态，历史明细由 databus_agent_step 审计表承担。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentCardChannel {

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final DatabusAgentMessageMapper messageMapper;
    private final DatabusAgentRunMapper runMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 注册本次 run 的 SSE 连接。
     *
     * @param runId   run 句柄
     * @param emitter 对话流
     */
    public void register(String runId, SseEmitter emitter) {
        if (runId == null || emitter == null) {
            return;
        }
        emitters.put(runId, emitter);
    }

    /**
     * 注销连接。按引用比对，避免误删同一 run 的新连接。
     *
     * @param runId   run 句柄
     * @param emitter 对话流
     */
    public void unregister(String runId, SseEmitter emitter) {
        if (runId == null || emitter == null) {
            return;
        }
        emitters.remove(runId, emitter);
    }

    /**
     * 把卡片推给前端，并落库供刷新后还原。
     *
     * @param runId run 句柄
     * @param card  卡片
     * @return true = 已写入连接；false = 连接不存在或已断开（卡片仍已落库，刷新可见）
     */
    public boolean send(String runId, AgentCard card) {
        if (runId == null || card == null) {
            return false;
        }
        persist(runId, card);
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("type", EVENT_CARD);
        payload.put("data", card);
        try {
            emitter.send(SseEmitter.event().name("message").data(payload, MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("AI 卡片回写失败 runId={}", runId, e);
            return false;
        }
    }

    /**
     * 把工具阶段推给前端。
     * <p>
     * 阶段是会话里的瞬时状态，不落库、不回放；历史明细由 databus_agent_step 审计表承担。
     * 连接不存在时直接忽略，不影响工具本身。
     *
     * @param runId run 句柄
     * @param step  阶段快照
     */
    public void sendStep(String runId, AgentStepEvent step) {
        if (runId == null || step == null) {
            return;
        }
        SseEmitter emitter = emitters.get(runId);
        if (emitter == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", runId);
        payload.put("type", AgentStepStatusEnum.RUNNING.getCode().equals(step.status()) ? EVENT_STEP_START : EVENT_STEP_END);
        payload.put("data", step);
        try {
            emitter.send(SseEmitter.event().name("message").data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("AI 阶段回写失败 runId={}", runId, e);
        }
    }

    /**
     * 卡片落库：与 step / message 共用 run.next_seq 号源，写失败只记日志，不影响卡片推送。
     *
     * @param runId run 句柄
     * @param card  卡片
     */
    private void persist(String runId, AgentCard card) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                runMapper.increaseNextSeq(runId);
                Integer nextSeq = runMapper.selectNextSeq(runId);
                DatabusAgentMessage message = new DatabusAgentMessage();
                message.setRunId(runId);
                message.setSeq(nextSeq == null ? 1 : nextSeq - 1);
                message.setEventCode(EVENT_CARD);
                message.setContent(JsonUtils.toJsonString(card));
                messageMapper.insert(message);
            });
        } catch (Exception e) {
            log.warn("[databus-agent] 卡片落库失败 runId={}", runId, e);
        }
    }
}