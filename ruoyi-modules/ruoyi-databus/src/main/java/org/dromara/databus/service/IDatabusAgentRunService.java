package org.dromara.databus.service;

import org.dromara.databus.domain.bo.AgentChatBo;
import org.dromara.databus.domain.vo.AgentChatStartVo;
import org.dromara.databus.domain.vo.AgentApprovalModeVo;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentMessageVo;
import org.dromara.databus.domain.vo.AgentRollbackVo;
import org.dromara.databus.domain.vo.AgentRevertPreviewVo;
import org.dromara.databus.domain.vo.AgentRunVo;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI agent 任务服务：建 run、流式对话、停止、回放。
 * <p>
 * run 的状态一律归后端（前端只存 conversationId），同链路同时只允许一个进行中的 run。
 *
 * @author databus
 */
public interface IDatabusAgentRunService {

    /**
     * 发起一次对话：建 run 落库，返回 run 句柄与会话 id。
     *
     * @param bo 对话入参
     * @return run 句柄与会话 id
     */
    AgentChatStartVo startChat(AgentChatBo bo);

    /**
     * 打开 run 的 SSE 流：后端向 SnailAI 发起 chatStream，逐事件转发给前端。
     *
     * @param runId run 句柄
     * @return SSE 连接
     */
    SseEmitter openStream(String runId);

    /**
     * 停止进行中的 run（用户点停止）。
     *
     * @param runId run 句柄
     */
    void stop(String runId);

    /**
     * 记录「用户丢弃本次画布更新」：落一行审计，下一轮上下文摘要里让模型知道改动没被采纳。
     *
     * @param runId     run 句柄
     * @param chainName 链路名，可为空
     */
    void reportCanvasSkip(String runId, String chainName);

    /**
     * 按会话查当前用户进行中的 run（刷新后恢复用），无则返回 null。
     *
     * @param conversationId 会话 id
     * @return 进行中的 run
     */
    AgentRunVo getActiveRun(String conversationId);

    /**
     * 回放某个 run 已落库的消息（按 seq 升序）。
     *
     * @param runId run 句柄
     * @return 消息列表
     */
    List<AgentMessageVo> listMessages(String runId);

    /**
     * 按会话回放历史轮次（按创建时间升序，取最近若干条）。
     * <p>
     * 刷新页面后靠 run 的 intent + report 还原对话，不重放 SSE 事件。
     *
     * @param conversationId 会话 id
     * @return 历史 run 列表
     */
    List<AgentRunVo> listConversationRuns(String conversationId);

    /**
     * 列出当前用户的会话摘要（置顶优先，其次按最近活跃倒序）。
     *
     * @param limit 条数上限，空取默认 50，最多 100
     * @return 会话摘要列表
     */
    List<AgentConversationVo> listConversations(Integer limit);

    /**
     * 搜当前用户的会话：命中自定义标题、任一轮问句、任一条消息内容即算命中。
     *
     * @param keyword 关键字，空则返回空列表
     * @param limit   条数上限，空取默认 50，最多 100
     * @return 命中的会话摘要
     */
    List<AgentConversationVo> searchConversations(String keyword, Integer limit);

    /**
     * 重命名会话：写 / 覆盖 meta 的自定义标题。
     *
     * @param conversationId 会话 id
     * @param title          新标题
     */
    void renameConversation(String conversationId, String title);

    /**
     * 置顶 / 取消置顶会话。
     *
     * @param conversationId 会话 id
     * @param pinned         true 置顶，false 取消
     */
    void pinConversation(String conversationId, boolean pinned);

    /**
     * 删除会话：只软删 meta 行，run / step / message 历史保留。
     *
     * @param conversationId 会话 id
     */
    void deleteConversation(String conversationId);

    /**
     * 撤销删除会话：把 meta 行的 del_flag 复位，历史原样回来。
     *
     * @param conversationId 会话 id
     */
    void restoreConversation(String conversationId);

    /**
     * 判断该轮是否真正改过链路（存在链路快照即为 true），前端据此决定是否显示回退入口。
     *
     * @param runId run 句柄
     * @return true 表示本轮动过链路
     */
    boolean hasRunChanges(String runId);

    /**
     * 删除一轮对话：逻辑删除该 run，历史回放不再出现这一轮。
     *
     * @param runId run 句柄
     */
    void deleteRun(String runId);

    /**
     * 原地重试：隐藏旧 run，沿用原意图重新建一个 run（前端只替换这一条回复）。
     *
     * @param runId 被重试的旧 run 句柄
     * @return 新 run 句柄与会话 id
     */
    AgentChatStartVo retryRun(String runId);

    /**
     * 回退预检：只读算出「将撤销 / 将跳过 / 受影响」，前端据此决定是否出确认卡。
     *
     * @param runId run 句柄
     * @return 预检结果，三份清单都为空表示这一轮没有可回退的改动
     */
    AgentRevertPreviewVo previewRevert(String runId);

    /**
     * 回退到本轮对话发起前：由近及远撤销本轮及之后各轮的链路与草稿改动，
     * 并删除这些轮次的历史，刷新后不再回放。撤不掉的资源跳过并附原因。
     *
     * @param runId run 句柄
     * @return 每个资源一条结果（action=SKIP 表示未撤销）
     */
    List<AgentRollbackVo> revertRun(String runId);

    /**
     * 读取会话级操作审批模式（无记录按 MANUAL，FULL 在部署未开放时归一为 MANUAL）。
     *
     * @param conversationId 会话 id
     * @return 生效模式与部署开关
     */
    AgentApprovalModeVo getApprovalMode(String conversationId);

    /**
     * 保存会话级操作审批模式。
     *
     * @param conversationId 会话 id
     * @param mode           模式编码 MANUAL/AUTO/FULL/CUSTOM
     */
    void saveApprovalMode(String conversationId, String mode);

}