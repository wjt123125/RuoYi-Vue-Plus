package org.dromara.databus.agent.run;

import org.dromara.databus.agent.event.AgentError;
import cn.hutool.core.lang.Dict;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.enums.AgentRunStatusEnum;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.dromara.databus.constant.AgentConstants.EVENT_ERROR;
import static org.dromara.databus.constant.AgentConstants.EVENT_RUN_LIFECYCLE_ENDED;

/**
 * 对话 SSE 管道：只负责事件写出、错误信封落库、run 条件收尾与订阅释放。
 * <p>
 * 这里不做业务判断（上游事件含义、何时收尾由调用方决定），也不持有任何会话状态。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRunStreamSink {

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentMessageMapper messageMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 收尾：条件 UPDATE（RUNNING 才改），done 时把最终文字落一条 message（seq 走 run.next_seq 号源）。
     *
     * @param settled 是否已收尾（同一 run 只允许一次）
     * @param run     任务
     * @param status  目标状态
     * @param report  最终文字，可为空
     */
    public boolean settle(AtomicBoolean settled, DatabusAgentRun run, AgentRunStatusEnum status, String report) {
        if (!settled.compareAndSet(false, true)) {
            return false;
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
        return true;
    }

    /**
     * 落一条脱敏错误事件（供刷新回放），原始异常不落库。
     *
     * @param runId run 句柄
     * @param error 错误信封
     */
    public void persistRunError(String runId, AgentError error) {
        try {
            transactionTemplate.executeWithoutResult(tx ->
                appendMessage(runId, EVENT_ERROR, null, JsonUtils.toJsonString(error)));
        } catch (Exception e) {
            log.warn("[databus-agent] 错误事件落库失败 runId={}", runId, e);
        }
    }

    /**
     * 落一条 message 并占用一个 seq（须在事务内调用，靠 run 行锁保序）。
     *
     * @param runId     run 句柄
     * @param eventCode 事件编码
     * @param chainId   链路 id，可为空
     * @param content   载荷
     */
    public void appendMessage(String runId, String eventCode, Long chainId, String content) {
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
     * 向 SSE 发一条事件（载荷为 JSON 对象，避免字符串默认编码把中文写坏）。
     *
     * @param emitter SSE 连接
     * @param runId   run 句柄
     * @param type    事件类型
     * @param data    事件数据
     */
    public void send(SseEmitter emitter, String runId, String type, String data) {
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
    public String extractDoneText(String raw) {
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
    public String extractContent(String raw) {
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
    public void sendQuietly(SseEmitter emitter, String runId, String type, String data) {
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
    public void dispose(Disposable[] holder) {
        Disposable disposable = holder[0];
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }
}
