package org.dromara.databus.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.dromara.databus.domain.vo.AgentSnapshotVo;
import org.dromara.databus.service.IDatabusAgentRunService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 助手对话控制器（数据总线工作区左侧栏用）。
 * <p>
 * 两步式：先 POST /chat 建 run 拿 runId，再用 EventSource 打开 /chat/{runId}/stream
 * （EventSource 不能带 body / 自定义头，鉴权走既有 ?Authorization=Bearer token 约定）。
 * run 状态、会话恢复、停止全部落在后端，前端只存 conversationId。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/agent")
@ConditionalOnProperty(prefix = "snail-ai.open-api", name = "enabled", havingValue = "true")
public class AgentChatController extends BaseController {

    private final IDatabusAgentRunService agentRunService;

    /**
     * 发起一次对话，返回 run 句柄与会话 id。
     *
     * @param bo 对话入参
     * @return run 句柄与会话 id
     */
    @PostMapping("/chat")
    public R<AgentChatStartVo> chat(@Valid @RequestBody AgentChatBo bo) {
        return R.ok(agentRunService.startChat(bo));
    }

    /**
     * 打开 run 的 SSE 流（逐事件转发 SnailAI 的 text / thinking / done / error）。
     *
     * @param runId    run 句柄
     * @param response 当前响应
     * @return SSE 连接
     */
    @GetMapping(value = "/chat/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId, HttpServletResponse response) {
        // SaInterceptor 鉴权通过时会把响应默认设成 application/json，这里必须覆盖回 text/event-stream，
        // 否则 EventSource 会因为 MIME 类型不符直接 Abort（SseEmitter 只在 Content-Type 为空时才补写）
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return agentRunService.openStream(runId);
    }

    /**
     * 停止进行中的 run。
     *
     * @param runId run 句柄
     */
    @PostMapping("/run/{runId}/stop")
    public R<Void> stop(@PathVariable String runId) {
        agentRunService.stop(runId);
        return R.ok();
    }

    /**
     * 按会话查当前用户进行中的 run（刷新后恢复用）。
     *
     * @param conversationId 会话 id
     * @return 进行中的 run，无则 data 为 null
     */
    @GetMapping("/run/active")
    public R<AgentRunVo> activeRun(@RequestParam @NotBlank String conversationId) {
        return R.ok(agentRunService.getActiveRun(conversationId));
    }

    /**
     * 回放某个 run 已落库的消息（按 seq 升序）。
     *
     * @param runId run 句柄
     * @return 消息列表
     */
    @GetMapping("/run/{runId}/messages")
    public R<List<AgentMessageVo>> messages(@PathVariable String runId) {
        return R.ok(agentRunService.listMessages(runId));
    }

    /**
     * 按会话回放历史轮次（刷新后还原对话用，按创建时间升序）。
     *
     * @param conversationId 会话 id
     * @return 历史 run 列表
     */
    @GetMapping("/conversation/{conversationId}/history")
    public R<List<AgentRunVo>> history(@PathVariable String conversationId) {
        return R.ok(agentRunService.listConversationRuns(conversationId));
    }

    /**
     * 列出某个 run 每轮动手前的快照（「回到 AI 动手前」按轮次选择用）。
     *
     * @param runId run 句柄
     * @return 轮次快照（不含 payload）
     */
    @GetMapping("/run/{runId}/snapshots")
    public R<List<AgentSnapshotVo>> snapshots(@PathVariable String runId) {
        return R.ok(agentRunService.listSnapshots(runId));
    }

    /**
     * 回到 AI 动手前：把指定轮次的快照写回链路。
     *
     * @param runId run 句柄
     * @param round 轮次
     * @return 被回滚的链路 id
     */
    @PostMapping("/run/{runId}/rollback")
    public R<Long> rollback(@PathVariable String runId, @RequestParam Integer round) {
        return R.ok(agentRunService.rollback(runId, round));
    }

}