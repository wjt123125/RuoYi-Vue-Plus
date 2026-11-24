package org.dromara.databus.agent;

import com.jayway.jsonpath.JsonPath;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.bo.PreviewRunBo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.domain.vo.PreviewRunVo;
import org.dromara.databus.enums.AgentApprovalModeEnum;
import org.dromara.databus.executor.DatabusExecutionResult;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.IDatabusPreviewRunService;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.dromara.databus.agent.AgentToolText.trimToNull;
import static org.dromara.databus.constant.AgentConstants.ACTION_CONFIRM;
import static org.dromara.databus.constant.AgentConstants.ERROR_CONFIG;
import static org.dromara.databus.constant.AgentConstants.ERROR_ENVIRONMENT;
import static org.dromara.databus.constant.AgentConstants.ERROR_EXTERNAL;
import static org.dromara.databus.constant.AgentConstants.ERROR_PERMISSION;
import static org.dromara.databus.constant.AgentConstants.ERROR_REQUIRE_APPROVAL;
import static org.dromara.databus.constant.AgentConstants.ERROR_RUNTIME;
import static org.dromara.databus.constant.AgentConstants.ERROR_TIMEOUT;
import static org.dromara.databus.constant.AgentConstants.ERROR_VALIDATION;
import static org.dromara.databus.constant.AgentConstants.MAX_CHECK_VALUE_BYTES;
import static org.dromara.databus.constant.AgentConstants.MAX_ERROR_TEXT;
import static org.dromara.databus.constant.AgentConstants.MAX_NODE_ERRORS;
import static org.dromara.databus.constant.AgentConstants.MAX_OUTPUT_VALUE_BYTES;
import static org.dromara.databus.constant.AgentConstants.TOOL_CHAIN_RUN;
import static org.dromara.databus.constant.CommonConstants.OWNER_SYSTEM;
import static org.dromara.databus.constant.CommonConstants.STATUS_DRAFT;

/**
 * 链路试跑工具的业务实现：审批闸、真实执行、输出裁剪与节点级错误分类，只回摘要不落执行记录。
 * <p>
 * 由 DatabusAgentTools 委托调用，不直接注册为工具。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentChainRunSupport {

    private final AgentGuard agentGuard;
    private final IDatabusChainService chainService;
    private final IDatabusPreviewRunService previewRunService;
    private final AgentApprovalService approvalService;
    private final AgentCardChannel cardChannel;
    private final DatabusAgentProperties properties;

    public Map<String, Object> doChainRun(AgentRunContext context, Long chainId, String payloadJson,
                                          Boolean userConfirmed, String outputPaths, String expect) {
        if (chainId == null) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        DatabusChainVo chain = chainService.queryById(chainId);
        if (chain == null) {
            throw new IllegalArgumentException("链路 " + chainId + " 不存在或已删除");
        }
        if (OWNER_SYSTEM.equals(chain.getOwnerType())) {
            throw new IllegalArgumentException("系统链路不能试跑，请先复制成自己的草稿链路");
        }
        if (!STATUS_DRAFT.equals(chain.getStatus())) {
            throw new IllegalArgumentException("只能试跑草稿态链路，当前状态为 " + chain.getStatus());
        }
        if (!Objects.equals(chain.getCreateBy(), context.userId())) {
            throw new IllegalArgumentException("只能试跑自己创建的链路");
        }

        // 会话级审批闸：判定只看规则扫描链路事实；userConfirmed 必须对得上本轮卡片回执
        AgentApprovalModeEnum mode = approvalService.getMode(context.conversationId(), context.userId());
        AgentApprovalService.Impact impact = approvalService.scan(chain.getCmpProperty());
        boolean confirmed = Boolean.TRUE.equals(userConfirmed) && agentGuard.hasCardConfirm(context.runId());
        AgentApprovalService.Decision decision = approvalService.decide(mode, impact, confirmed);
        if (!decision.allowed()) {
            AgentCard card = buildApprovalCard(context, chain, impact, payloadJson, outputPaths, expect);
            boolean delivered = cardChannel.send(context.runId(), card);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", false);
            payload.put("errorType", ERROR_REQUIRE_APPROVAL);
            payload.put("mode", mode.getCode());
            payload.put("cardId", card.cardId());
            payload.put("cardDelivered", delivered);
            payload.put("impact", impactPayload(impact));
            payload.put("message", approvalMessage(impact, decision, delivered));
            return payload;
        }
        log.info("[databus-agent] chain_run 放行 conversationId={} chainId={} mode={} autoPass={} reason={}",
            context.conversationId(), chainId, mode.getCode(), decision.autoPass(), decision.reason());

        PreviewRunBo bo = new PreviewRunBo();
        bo.setJsonEl(chain.getCmpProperty());
        bo.setInputParams(chain.getInputParams());
        bo.setRequestJson(payloadJson);
        PreviewRunVo result = previewRunService.run(bo);

        List<Map<String, Object>> nodeErrors = new ArrayList<>();
        boolean truncated = false;
        if (result.getSteps() != null) {
            for (DatabusExecutionResult.NodeStep step : result.getSteps()) {
                if (step.isSuccess()) {
                    continue;
                }
                if (nodeErrors.size() >= MAX_NODE_ERRORS) {
                    truncated = true;
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("nodeId", step.getNodeId());
                item.put("nodeName", step.getNodeName());
                item.put("title", step.getTitle());
                item.put("error", truncate(step.getErrorMessage(), MAX_ERROR_TEXT));
                nodeErrors.add(item);
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chainId", chain.getId());
        payload.put("chainName", chain.getChainName());
        payload.put("executionId", result.getExecutionId());
        payload.put("success", Boolean.TRUE.equals(result.getSuccess()));
        payload.put("costMs", result.getCostTime());
        payload.put("valid", result.getValid());
        payload.put("executed", result.getExecuted());
        payload.put("approval", approvalAudit(mode, decision));
        if (!Boolean.TRUE.equals(result.getValid()) || !Boolean.TRUE.equals(result.getExecuted())) {
            // EL 生成/校验没过：给出校验原因，节点还没跑
            payload.put("message", result.getMessage());
            payload.put("errorType", ERROR_VALIDATION);
            payload.put("errorReason", truncate(result.getMessage(), MAX_ERROR_TEXT));
            payload.put("retryable", true);
        } else if (!Boolean.TRUE.equals(result.getSuccess())) {
            String reason = truncate(result.getErrorMessage(), MAX_ERROR_TEXT);
            payload.put("message", reason);
            String errorType = classifyError(reason, nodeErrors);
            payload.put("errorType", errorType);
            payload.put("errorReason", reason);
            payload.put("retryable", isRetryable(errorType));
        } else {
            payload.put("output", buildOutput(result.getContextJson(), outputPaths));
            payload.put("checks", buildChecks(result.getContextJson(), expect));
        }
        payload.put("nodeErrors", nodeErrors);
        payload.put("truncated", truncated);
        return payload;
    }

    /**
     * 审批拦截时预填确认卡：事实来自规则扫描，不让模型自由发挥影响清单。
     * <p>
     * 选项必须带 description——前端把 label 当按钮文案、description 当按钮的悬浮说明，
     * 用户不点开也能看清「点了会发生什么」。
     *
     * @param context     调用上下文
     * @param chain       待执行链路
     * @param impact      规则扫描结果
     * @param payloadJson 试跑入参
     * @param outputPaths 输出路径
     * @param expect      断言
     * @return 确认卡
     */
    private static AgentCard buildApprovalCard(AgentRunContext context, DatabusChainVo chain,
                                               AgentApprovalService.Impact impact, String payloadJson,
                                               String outputPaths, String expect) {
        List<String> riskyNames = new ArrayList<>();
        for (AgentApprovalService.ImpactNode node : impact.nodes()) {
            if (isRisky(node.kind())) {
                riskyNames.add(node.name() + "（" + node.reason() + "）");
            }
        }
        StringBuilder body = new StringBuilder();
        body.append("会真实执行：写节点 ")
            .append(impact.writeCount()).append(" 个、外部调用节点 ")
            .append(impact.externalCount()).append(" 个。");
        if (!riskyNames.isEmpty()) {
            body.append("需要确认的节点：").append(String.join("、", riskyNames)).append("。");
        }
        List<AgentCard.Option> options = List.of(
            new AgentCard.Option(ACTION_CONFIRM, "确认执行",
                "按影响清单真实执行一次", "primary"),
            new AgentCard.Option("cancel", "取消",
                "不执行，回到对话继续调整链路", "default"));
        String runId = context.runId() == null ? "run" : context.runId();
        String cardId = runId + ":card-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return AgentCard.confirm(cardId, "试跑「" + chain.getChainName() + "」", body.toString(),
            buildRunDetail(chain, payloadJson, outputPaths, expect), options);
    }

    /**
     * 确认卡的原文块：把真正要执行的那次调用按行摆出来，用户扫一眼就知道要跑什么。
     * <p>
     * 只列本次调用实际带的参数，空值不占行；不写解释性文案，解释留给 body。
     */
    private static String buildRunDetail(DatabusChainVo chain, String payloadJson, String outputPaths, String expect) {
        StringBuilder detail = new StringBuilder(TOOL_CHAIN_RUN).append('\n');
        detail.append("  chainId: ").append(chain.getId()).append('\n');
        detail.append("  chainName: ").append(chain.getChainName());
        String payload = trimToNull(payloadJson);
        if (payload != null) {
            detail.append('\n').append("  payload: ").append(payload);
        }
        String paths = trimToNull(outputPaths);
        if (paths != null) {
            detail.append('\n').append("  outputPaths: ").append(paths);
        }
        String assertions = trimToNull(expect);
        if (assertions != null) {
            detail.append('\n').append("  expect: ").append(assertions);
        }
        return detail.toString();
    }

    /**
     * 审批阻断时给模型看的影响清单：节点名 + 判定 + 依据，人话描述由模型转述。
     */
    private static Map<String, Object> impactPayload(AgentApprovalService.Impact impact) {
        List<Map<String, Object>> components = new ArrayList<>();
        for (AgentApprovalService.ImpactNode node : impact.nodes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", node.nodeId());
            item.put("code", node.code());
            item.put("name", node.name());
            item.put("kind", node.kind());
            item.put("reason", node.reason());
            components.add(item);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("writeCount", impact.writeCount());
        payload.put("externalCount", impact.externalCount());
        payload.put("needConfirm", impact.needConfirm());
        payload.put("components", components);
        // 同一份清单的别名：协议里 components / nodes 两种叫法都出现过，避免模型读错字段
        payload.put("nodes", components);
        return payload;
    }

    /**
     * 审批提示：卡片已负责把影响清单摆给用户，模型只需一句话提示，不再复述清单。
     */
    private static String approvalMessage(AgentApprovalService.Impact impact,
                                          AgentApprovalService.Decision decision, boolean cardDelivered) {
        StringBuilder sb = new StringBuilder();
        sb.append("本次拦截原因：").append(impact.writeCount()).append(" 个写节点、")
            .append(impact.externalCount()).append(" 个外部调用节点；")
            .append(decision.reason()).append("。");
        if (cardDelivered) {
            sb.append("确认卡片已在对话中展示，本轮到此为止：不要复述影响清单、不要用文字再问一遍，")
                .append("只需提示用户点卡片上的按钮。用户点确认后会收到【卡片回执】，")
                .append("那时再带 userConfirmed=true 重新调用。");
        } else {
            sb.append("确认卡片未能送达前端（连接可能已断开），本轮到此为止：")
                .append("请提示用户刷新后重试，不要带 userConfirmed=true 直接重调。");
        }
        return sb.toString();
    }

    /**
     * 影响清单里需要人确认的判定：写类与外部调用。
     */
    private static boolean isRisky(String kind) {
        return "WRITE".equals(kind) || "EXTERNAL".equals(kind);
    }

    /**
     * 放行审计：写进 step 结果，自动放行的 reason 必须留痕。
     */
    private static Map<String, Object> approvalAudit(AgentApprovalModeEnum mode,
                                                     AgentApprovalService.Decision decision) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("mode", mode.getCode());
        audit.put("decision", decision.autoPass() ? "AUTO_PASS" : "USER_CONFIRMED");
        audit.put("reason", decision.reason());
        return audit;
    }

    /**
     * 输出回传：按 outputPaths 取值；不传则回上下文顶层摘要。总量受 max-output-bytes 约束。
     */
    private Map<String, Object> buildOutput(String contextJson, String outputPaths) {
        int limit = properties.getMaxOutputBytes() > 0 ? properties.getMaxOutputBytes() : 16384;
        Map<String, Object> output = new LinkedHashMap<>();
        if (contextJson == null || contextJson.isBlank()) {
            output.put("summary", List.of());
            output.put("totalBytes", 0);
            output.put("oversize", false);
            return output;
        }
        List<String> paths = parseStringArray(outputPaths);
        if (paths.isEmpty()) {
            List<Map<String, Object>> summary = summarizeContext(contextJson);
            output.put("summary", summary);
            output.put("totalBytes", contextJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            output.put("oversize", contextJson.length() > limit);
            return output;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int used = 0;
        boolean oversize = false;
        for (String path : paths) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            try {
                Object value = JsonPath.read(contextJson, path);
                String text = JsonUtils.toJsonString(value);
                int bytes = text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (bytes > MAX_OUTPUT_VALUE_BYTES) {
                    item.put("value", truncate(text, MAX_OUTPUT_VALUE_BYTES));
                    item.put("truncated", true);
                } else {
                    item.put("value", value);
                }
                if (used + Math.min(bytes, MAX_OUTPUT_VALUE_BYTES) > limit) {
                    oversize = true;
                    item.put("value", truncate(text, MAX_OUTPUT_VALUE_BYTES));
                    item.put("truncated", true);
                }
                used += Math.min(bytes, MAX_OUTPUT_VALUE_BYTES);
            } catch (Exception e) {
                item.put("error", "路径不存在或无法读取");
            }
            items.add(item);
        }
        output.put("items", items);
        output.put("totalBytes", used);
        output.put("oversize", oversize);
        return output;
    }

    /**
     * 不指定路径时只回顶层字段形状，避免把整包上下文塞进模型。
     */
    private static List<Map<String, Object>> summarizeContext(String contextJson) {
        List<Map<String, Object>> summary = new ArrayList<>();
        Map<String, Object> map = JsonUtils.parseMap(contextJson);
        if (map == null) {
            return summary;
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.getKey());
            Object value = entry.getValue();
            item.put("type", value == null ? "null"
                : value instanceof List<?> ? "array"
                : value instanceof Map<?, ?> ? "object"
                : value instanceof Number ? "number"
                : value instanceof Boolean ? "boolean" : "string");
            if (value instanceof List<?> list) {
                item.put("count", list.size());
            } else if (value instanceof Map<?, ?> object) {
                item.put("count", object.size());
            } else if (value instanceof String text) {
                item.put("bytes", text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            summary.add(item);
        }
        return summary;
    }

    /**
     * 期望判定：只计算结果，不落库。
     */
    private static List<Map<String, Object>> buildChecks(String contextJson, String expect) {
        List<Map<String, Object>> checks = new ArrayList<>();
        List<Map<String, Object>> items = parseObjectArray(expect);
        if (items.isEmpty() || contextJson == null || contextJson.isBlank()) {
            return checks;
        }
        for (Map<String, Object> item : items) {
            String path = item.get("path") == null ? null : String.valueOf(item.get("path"));
            String op = item.get("op") == null ? "eq" : String.valueOf(item.get("op")).toLowerCase(Locale.ROOT);
            Object expected = item.get("value");
            Map<String, Object> check = new LinkedHashMap<>();
            check.put("path", path);
            check.put("op", op);
            check.put("expected", expected);
            if (path == null || path.isBlank()) {
                check.put("pass", false);
                check.put("error", "path 不能为空");
                checks.add(check);
                continue;
            }
            Object actual;
            try {
                actual = JsonPath.read(contextJson, path);
            } catch (Exception e) {
                check.put("pass", false);
                check.put("error", "路径不存在");
                checks.add(check);
                continue;
            }
            check.put("actual", truncate(JsonUtils.toJsonString(actual), MAX_CHECK_VALUE_BYTES));
            check.put("pass", compare(actual, op, expected));
            checks.add(check);
        }
        return checks;
    }

    private static boolean compare(Object actual, String op, Object expected) {
        return switch (op) {
            case "exists" -> actual != null;
            case "eq" -> valueEquals(actual, expected);
            case "ne" -> !valueEquals(actual, expected);
            case "contains" -> actual != null && String.valueOf(actual).contains(String.valueOf(expected));
            case "gt", "gte", "lt", "lte" -> {
                BigDecimal left = toDecimal(actual);
                BigDecimal right = toDecimal(expected);
                if (left == null || right == null) {
                    yield false;
                }
                int cmp = left.compareTo(right);
                yield switch (op) {
                    case "gt" -> cmp > 0;
                    case "gte" -> cmp >= 0;
                    case "lt" -> cmp < 0;
                    default -> cmp <= 0;
                };
            }
            default -> false;
        };
    }

    private static boolean valueEquals(Object actual, Object expected) {
        BigDecimal left = toDecimal(actual);
        BigDecimal right = toDecimal(expected);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        return Objects.equals(actual, expected)
            || (actual != null && expected != null && String.valueOf(actual).equals(String.valueOf(expected)));
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 错误分类：给模型一个「该改结构还是该重试」的判断依据。
     */
    private static String classifyError(String reason, List<Map<String, Object>> nodeErrors) {
        StringBuilder text = new StringBuilder(reason == null ? "" : reason);
        for (Map<String, Object> item : nodeErrors) {
            Object error = item.get("error");
            if (error != null) {
                text.append(' ').append(error);
            }
        }
        String lower = text.toString().toLowerCase(Locale.ROOT);
        if (lower.contains("超时") || lower.contains("timeout") || lower.contains("timed out")) {
            return ERROR_TIMEOUT;
        }
        if (lower.contains("权限") || lower.contains("forbidden") || lower.contains("unauthorized")
            || lower.contains("403")) {
            return ERROR_PERMISSION;
        }
        if (lower.contains("连接") || lower.contains("connect") || lower.contains("refused")
            || lower.contains("unknown host") || lower.contains("no route") || lower.contains("dns")) {
            return ERROR_ENVIRONMENT;
        }
        if (lower.contains("http") || lower.contains("外部") || lower.contains("接口")
            || lower.contains("status=") || lower.contains("响应")) {
            return ERROR_EXTERNAL;
        }
        if (lower.contains("参数") || lower.contains("配置") || lower.contains("必填")
            || lower.contains("不能为空") || lower.contains("缺少") || lower.contains("未配置")
            || lower.contains("脚本") || lower.contains("表达式")) {
            return ERROR_CONFIG;
        }
        return ERROR_RUNTIME;
    }

    private static boolean isRetryable(String errorType) {
        return ERROR_VALIDATION.equals(errorType) || ERROR_CONFIG.equals(errorType)
            || ERROR_RUNTIME.equals(errorType) || ERROR_TIMEOUT.equals(errorType)
            || ERROR_EXTERNAL.equals(errorType);
    }

    private static List<String> parseStringArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = JsonUtils.parseArray(json, String.class);
            return list == null ? List.of() : list.stream().filter(Objects::nonNull).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<Map<String, Object>> parseObjectArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> item : JsonUtils.parseArrayMap(json)) {
                list.add(item);
            }
            return list;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
