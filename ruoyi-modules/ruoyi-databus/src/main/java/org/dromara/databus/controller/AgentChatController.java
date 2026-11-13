package org.dromara.databus.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.AgentRunVo;
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
     * 上报「用户丢弃了本次画布更新」，记入审计供下一轮对话得知改动未采纳。
     *
     * @param runId     run 句柄
     * @param chainName 链路名，可为空
     */
    @PostMapping("/run/{runId}/canvas-skip")
    public R<Void> canvasSkip(@PathVariable String runId, @RequestParam(required = false) String chainName) {
        agentRunService.reportCanvasSkip(runId, chainName);
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
     * 列出当前用户的会话（按最近活跃倒序），供工作区左侧历史栏使用。
     *
     * @param limit 条数上限，默认 50，最多 100
     * @return 会话摘要列表
     */
    @GetMapping("/conversations")
    public R<List<AgentConversationVo>> conversations(
        @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return R.ok(agentRunService.listConversations(limit));
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
     * 回到这次对话前：按 run 整体回滚（新建的删掉、改过的写回）。
     *
     * @param runId run 句柄
     * @return 被回滚的链路
     */
    @PostMapping("/run/{runId}/rollback")
    public R<List<AgentRollbackVo>> rollback(@PathVariable String runId) {
        return R.ok(agentRunService.rollbackRun(runId));
    }

}