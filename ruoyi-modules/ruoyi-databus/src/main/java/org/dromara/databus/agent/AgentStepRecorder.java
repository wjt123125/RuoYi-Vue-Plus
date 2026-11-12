package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.domain.DatabusAgentStep;
import org.dromara.databus.mapper.DatabusAgentRunMapper;
import org.dromara.databus.mapper.DatabusAgentStepMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.regex.Pattern;

/**
 * 工具调用审计统一入口（方案 §十五 守卫 ⑦）。
 * <p>
 * 每次工具调用必落一行 databus_agent_step：seq 与 message 共用 run.next_seq 号源，
 * 同一事务内条件自增，避免并发撞唯一键。审计写入失败只记日志，不影响工具返回与主流程。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentStepRecorder {

    /**
     * 与 DDL 列宽一致，超长截断。
     */
    private static final int MAX_ARGS_LEN = 2000;
    private static final int MAX_RESULT_LEN = 4000;
    private static final int MAX_ERROR_LEN = 2000;
    private static final int MAX_REJECT_LEN = 200;

    /**
     * 出库脱敏：仅遮蔽键名明确的凭据字段，避免把库里的密码 / token 写进审计。
     */
    private static final Pattern SECRET_FIELD = Pattern.compile(
        "(?i)\"(password|passwd|pwd|token|secret|api_?key|access_?key|secret_?key|credentials?)\"\\s*:\\s*\"[^\"]*\"");

    private final DatabusAgentRunMapper runMapper;
    private final DatabusAgentStepMapper stepMapper;
    private final TransactionTemplate transactionTemplate;

    /**
     * 记录成功调用。
     *
     * @param context  身份快照
     * @param tool     工具名
     * @param argsJson 入参 JSON
     * @param result   返回 JSON
     * @param costMs   耗时毫秒
     */
    public void recordSuccess(AgentRunContext context, String tool, String argsJson, String result, long costMs) {
        if (context == null) {
            log.warn("[databus-agent] 审计跳过：身份快照为空 tool={}", tool);
            return;
        }
        insert(context.runId(), context.round(), tool, argsJson, result, "1", null, null, costMs);
    }

    /**
     * 记录守卫拒绝（§十五 ②–⑤）。
     *
     * @param runId  被拒绝的 run，可为空
     * @param tool   工具名
     * @param argsJson 入参 JSON
     * @param result 返回给模型的错误 JSON
     * @param reason 拒绝原因
     * @param costMs 耗时毫秒
     */
    public void recordReject(String runId, String tool, String argsJson, String result, String reason, long costMs) {
        if (runId == null || runId.isBlank()) {
            log.warn("[databus-agent] 审计跳过：上下文缺失无法定位 run tool={} reason={}", tool, reason);
            return;
        }
        insert(runId, 0, tool, argsJson, result, "0", null, reason, costMs);
    }

    /**
     * 记录执行失败（守卫通过后业务异常）。
     *
     * @param context 身份快照，可为空
     * @param tool    工具名
     * @param argsJson 入参 JSON
     * @param result  返回给模型的错误 JSON
     * @param error   异常摘要
     * @param costMs  耗时毫秒
     */
    public void recordFailure(AgentRunContext context, String tool, String argsJson, String result, String error, long costMs) {
        if (context == null) {
            log.warn("[databus-agent] 审计跳过：身份快照为空 tool={} error={}", tool, error);
            return;
        }
        insert(context.runId(), context.round(), tool, argsJson, result, "0", error, null, costMs);
    }

    private void insert(String runId, Integer round, String tool, String argsJson, String result,
                        String success, String errorMessage, String rejectReason, long costMs) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                runMapper.increaseNextSeq(runId);
                Integer nextSeq = runMapper.selectNextSeq(runId);
                DatabusAgentStep step = new DatabusAgentStep();
                step.setRunId(runId);
                step.setSeq(nextSeq == null ? 1 : nextSeq - 1);
                step.setRound(round == null ? 0 : round);
                step.setTool(tool);
                step.setArgsDigest(truncate(sanitize(argsJson), MAX_ARGS_LEN));
                step.setResultDigest(truncate(sanitize(result), MAX_RESULT_LEN));
                step.setSuccess(success);
                step.setErrorMessage(truncate(sanitize(errorMessage), MAX_ERROR_LEN));
                step.setGuardRejectReason(truncate(rejectReason, MAX_REJECT_LEN));
                step.setCostMs(costMs);
                stepMapper.insert(step);
            });
        } catch (Exception e) {
            log.warn("[databus-agent] 写审计 step 失败 runId={} tool={}", runId, tool, e);
        }
    }

    private static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return SECRET_FIELD.matcher(text).replaceAll("\"$1\":\"***\"");
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 3) + "...";
    }

}