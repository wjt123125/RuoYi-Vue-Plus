package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.enums.PushSourceEnum;
import org.dromara.common.core.enums.PushTypeEnum;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.push.helper.PushHelper;
import org.dromara.databus.domain.DatabusAgentMessage;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.mapper.DatabusAgentMessageMapper;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.system.api.domain.PushPayloadDTO;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI agent 业务事件发布器。
 * <p>
 * 画布/草稿这类「AI 已改完、前端要跟着动」的业务事件走全局推送通道，
 * 不塞进 {@code AgentChatController} 的按 run SSE：那条流是对话文字，
 * 关掉面板还会断；业务事件必须能被工作区任何页面收到。
 * <p>
 * seq 与 step 共用 run.next_seq（条件 UPDATE 原子自增），载荷必带 runId + seq，
 * 前端据此去重与保序；同时落 databus_agent_message 供刷新后回放。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentEventPublisher {

    /**
     * 画布已更新（全量 cmpProperty 快照）
     */
    public static final String EVENT_CANVAS = "databus:agent:canvas";

    /**
     * 草稿已落库（chainId）
     */
    public static final String EVENT_DRAFT = "databus:agent:draft";

    private final DatabusAgentRunMapper runMapper;

    private final DatabusAgentMessageMapper messageMapper;

    /**
     * 推「画布已更新」：带链路全量结构树与既有 EL，前端直接按四步重画。
     *
     * @param context      当前 run 上下文
     * @param chainId      链路 id
     * @param chainName    链路名称
     * @param cmpProperty  全量结构树
     * @param elExpression 后端生成的 EL
     * @param revealNodeId 需要高亮的节点 id（可空）
     * @param created      本次是否新建链路：新建时前端即使画布未打开也自动开 tab
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishCanvas(AgentRunContext context, Long chainId, String chainName,
                              CmpProperty cmpProperty, String elExpression, String revealNodeId,
                              boolean created) {
        int seq = nextSeq(context.runId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventCode", EVENT_CANVAS);
        data.put("runId", context.runId());
        data.put("conversationId", context.conversationId());
        data.put("seq", seq);
        data.put("chainId", chainId);
        data.put("chainName", chainName);
        data.put("operation", created ? "CREATE" : "UPDATE");
        data.put("cmpProperty", cmpProperty);
        data.put("elExpression", elExpression);
        if (revealNodeId != null) {
            data.put("revealNodeId", revealNodeId);
        }
        data.put("timestamp", System.currentTimeMillis());

        saveMessage(context.runId(), seq, EVENT_CANVAS, chainId, JsonUtils.toJsonString(cmpProperty));
        send(context, "画布已更新：" + (chainName == null ? chainId : chainName), data);
    }

    /**
     * 推「草稿已落库」：只带链路 id，前端刷新链树/角标。
     *
     * @param context   当前 run 上下文
     * @param chainId   链路 id
     * @param chainName 链路名称
     */
    @Transactional(rollbackFor = Exception.class)
    public void publishDraft(AgentRunContext context, Long chainId, String chainName) {
        int seq = nextSeq(context.runId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventCode", EVENT_DRAFT);
        data.put("runId", context.runId());
        data.put("conversationId", context.conversationId());
        data.put("seq", seq);
        data.put("chainId", chainId);
        data.put("chainName", chainName);
        data.put("timestamp", System.currentTimeMillis());

        saveMessage(context.runId(), seq, EVENT_DRAFT, chainId, null);
        send(context, "草稿已保存：" + (chainName == null ? chainId : chainName), data);
    }

    private int nextSeq(String runId) {
        runMapper.increaseNextSeq(runId);
        Integer seq = runMapper.selectNextSeq(runId);
        // 与 AgentStepRecorder、run 收尾的号源口径一致：自增后回读值减一才是本条的 seq
        return seq == null ? 1 : seq - 1;
    }

    private void saveMessage(String runId, int seq, String eventCode, Long chainId, String content) {
        DatabusAgentMessage message = new DatabusAgentMessage();
        message.setRunId(runId);
        message.setSeq(seq);
        message.setEventCode(eventCode);
        message.setChainId(chainId);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private void send(AgentRunContext context, String text, Map<String, Object> data) {
        try {
            PushHelper.sendMessage(context.userId(),
                PushPayloadDTO.of(PushTypeEnum.LLM, PushSourceEnum.LLM, text, data));
        } catch (Exception e) {
            // 推送失败不能回滚已落库的链路改动：业务事件是「通知」，不是「事务的一部分」
            log.warn("[databus-agent] 业务事件推送失败 runId={} event={}：{}",
                context.runId(), data.get("eventCode"), e.getMessage());
        }
    }

}