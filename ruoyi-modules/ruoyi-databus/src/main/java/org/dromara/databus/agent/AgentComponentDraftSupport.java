package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import static org.dromara.databus.agent.AgentToolText.trimToNull;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.bo.ScriptDraftBo;
import org.dromara.databus.domain.vo.ScriptDraftResultVo;
import org.dromara.databus.el.bean.ChainInputParam;
import org.dromara.databus.script.host.ScriptCompileException;
import org.dromara.databus.script.host.ScriptDiagnostic;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.dromara.databus.constant.AgentConstants.ERROR_VALIDATION;
import static org.dromara.databus.constant.AgentConstants.MAX_ERROR_TEXT;
import static org.dromara.databus.constant.AgentConstants.MAX_NODE_ERRORS;
import static org.dromara.databus.constant.AgentConstants.MAX_OUTPUT_VALUE_BYTES;
import static org.dromara.databus.constant.AgentConstants.RESOURCE_TYPE_COMPONENT;

/**
 * 组件草稿工具的业务实现：编译落 DRAFT 行、裁剪回给模型的载荷、推送审阅资源卡。
 * <p>
 * 由 DatabusAgentTools 委托调用，不直接注册为工具。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class AgentComponentDraftSupport {

    private final IDatabusComponentService componentService;
    private final AgentEventPublisher eventPublisher;
    private final AgentCardChannel cardChannel;

    /**
     * 落脚本草稿：service 负责编译与落 DRAFT 行，这里只做「回给模型什么」的裁剪。
     * <p>
     * 编译失败单独接住转 VALIDATION：模型拿到行列诊断才能自己改源码，甩成 RUNTIME 只会让它乱猜。
     *
     * @param context       调用上下文（取发起人写 create_by）
     * @param componentId   已有草稿 id
     * @param componentCode 组件编码
     * @param componentName 组件名称
     * @param scriptBody    完整 Java 源码
     * @param category      台账五分类
     * @param groupName     物料面板分组
     * @param description   组件描述
     * @param inputParams   试运行入参登记表 JSON 数组
     * @return 结果载荷
     */
    public Map<String, Object> doScriptDraftSave(AgentRunContext context, Long componentId,
                                                 String componentCode, String componentName, String scriptBody,
                                                 String category, String groupName, String description,
                                                 String inputParams) {
        ScriptDraftBo bo = new ScriptDraftBo();
        bo.setId(componentId);
        bo.setComponentCode(componentCode);
        bo.setComponentName(componentName);
        bo.setScriptBody(scriptBody);
        bo.setCategory(category);
        bo.setGroupName(groupName);
        bo.setDescription(description);
        bo.setInputParams(parseInputParams(inputParams));
        // create_by 只认会话身份：草稿所有权决定「谁能改」，不能让模型指定
        bo.setCreateBy(context.userId());
        try {
            ScriptDraftResultVo result = componentService.saveScriptDraft(bo);
            // 业务事件：工作区收到后刷组件树并自动开审阅 tab；
            // 对话里再补一张资源卡，用户关掉 tab 后仍有可点入口
            eventPublisher.publishComponentDraft(context, result.getId(),
                result.getComponentCode(), result.getComponentName());
            cardChannel.send(context.runId(), buildDraftReviewCard(context, result));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("draftId", result.getId());
            payload.put("componentCode", result.getComponentCode());
            payload.put("componentName", result.getComponentName());
            payload.put("status", result.getStatus());
            payload.put("nodeType", result.getNodeType());
            payload.put("fieldCount", result.getFieldCount());
            payload.put("paramSchema", truncate(result.getParamSchema(), MAX_OUTPUT_VALUE_BYTES));
            payload.put("dataExample", truncate(result.getDataExample(), MAX_OUTPUT_VALUE_BYTES));
            payload.put("inputParams", result.getInputParams());
            payload.put("risks", result.getRisks());
            payload.put("message", "草稿已落库，但尚未生效：需要人在组件审阅页点「采纳」后才会注册并投放物料面板。");
            return payload;
        } catch (ScriptCompileException e) {
            return compileErrorPayload(e);
        }
    }

    /**
     * 草稿资源卡：会话里留一个「打开审阅」入口，不参与确认/选择，点击只跳 tab。
     *
     * @param context 当前 run 上下文
     * @param result  落库结果
     * @return 资源卡
     */
    private static AgentCard buildDraftReviewCard(AgentRunContext context, ScriptDraftResultVo result) {
        String runId = context.runId() == null ? "run" : context.runId();
        String cardId = runId + ":card-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return AgentCard.ref(cardId,
            "草稿待审阅：" + result.getComponentName(),
            "采纳后才会注册进 FlowBus 并出现在物料面板。",
            new AgentCard.Resource(RESOURCE_TYPE_COMPONENT,
                String.valueOf(result.getId()), result.getComponentCode(), result.getComponentName(),
                Boolean.TRUE));
    }

    /**
     * 编译失败载荷：errorType 固定 VALIDATION，附行列诊断，标记可重试（改源码就能再调）。
     *
     * @param e 编译异常
     * @return 错误载荷
     */
    private static Map<String, Object> compileErrorPayload(ScriptCompileException e) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("errorType", ERROR_VALIDATION);
        payload.put("errorReason", "脚本编译失败");
        payload.put("message", truncate(e.getMessage(), MAX_ERROR_TEXT * 4));
        List<ScriptDiagnostic> diagnostics = e.getDiagnostics();
        if (diagnostics != null && !diagnostics.isEmpty()) {
            payload.put("diagnostics", diagnostics.stream()
                .limit(MAX_NODE_ERRORS)
                .map(d -> (d.line() > 0 ? "第" + d.line() + "行第" + d.column() + "列：" : "") + d.message())
                .toList());
        }
        payload.put("retryable", true);
        return payload;
    }

    private static List<ChainInputParam> parseInputParams(String json) {
        String text = trimToNull(json);
        if (text == null) {
            return null;
        }
        try {
            return JsonUtils.parseArray(text, ChainInputParam.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("inputParams 不是合法的 JSON 数组：" + e.getMessage());
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "…";
    }
}
