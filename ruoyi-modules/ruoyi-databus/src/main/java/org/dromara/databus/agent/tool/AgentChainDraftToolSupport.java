package org.dromara.databus.agent.tool;

import org.dromara.databus.agent.run.AgentRunContext;
import org.dromara.databus.agent.event.AgentEventPublisher;
import org.dromara.databus.agent.support.AgentGuard;
import lombok.RequiredArgsConstructor;
import static org.dromara.databus.agent.tool.AgentToolText.trimToNull;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.el.bean.ChainInputParam;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static org.dromara.databus.constant.ChainConstants.LOG_LEVEL_FULL;
import static org.dromara.databus.constant.CommonConstants.OWNER_SYSTEM;
import static org.dromara.databus.constant.CommonConstants.STATUS_DRAFT;

/**
 * 链路草稿工具的业务实现：生成 EL、校验结构、落快照后新建或修改草稿，只产生待发布内容。
 * <p>
 * 由 DatabusAgentTools 委托调用，不直接注册为工具。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class AgentChainDraftToolSupport {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentGuard agentGuard;
    private final IDatabusChainService chainService;
    private final IDatabusAgentSnapshotService snapshotService;
    private final AgentEventPublisher eventPublisher;
    private final DatabusExecutor databusExecutor;
    private final ExpressGenerator expressGenerator;

    public Map<String, Object> doChainSave(AgentRunContext context, Long draftId, String chainCode,
                                           String chainName, String cmpPropertyJson, String inputParamsJson,
                                           String logLevel, String note, String expectedUpdateTime, Boolean force) {
        CmpProperty tree = parseTree(cmpPropertyJson);
        // 内联脚本拦截：脚本只允许走 databus_component_draft_save，链路树里出现脚本叶子一律不写库
        List<DatabusExecutor.ScriptNodeSpec> scripts = databusExecutor.collectScriptNodes(tree);
        if (!scripts.isEmpty()) {
            throw new IllegalArgumentException("本链路含 " + scripts.size()
                + " 个内联脚本节点，必须先调 databus_component_draft_save 落草稿、再把节点换成组件引用");
        }
        int nodeCount = countNodes(tree);
        String level = normalizeLogLevel(logLevel);

        ELInfo elInfo;
        String verifyMessage;
        try {
            elInfo = expressGenerator.generateEL(tree);
            verifyMessage = expressGenerator.validateEL(tree);
        } catch (Exception e) {
            throw new IllegalArgumentException("结构树无法生成 EL（链路未写入）：" + e.getMessage());
        }
        if (verifyMessage != null) {
            throw new IllegalArgumentException("EL 校验不通过（链路未写入）：" + verifyMessage);
        }
        String elExpression = elInfo == null ? null : elInfo.getElStr();

        Long chainId;
        String name;
        boolean overwritten = false;
        boolean created = draftId == null;
        if (draftId == null) {
            String code = trimToNull(chainCode);
            name = trimToNull(chainName);
            if (code == null) {
                throw new IllegalArgumentException("新建链路必须给 chainCode");
            }
            if (name == null) {
                throw new IllegalArgumentException("新建链路必须给 chainName");
            }
            // 新建也占一轮：回滚时按 operation=CREATE 删除整条链路
            int round = agentGuard.nextRound(context.runId());
            DatabusChainBo bo = new DatabusChainBo();
            bo.setChainCode(code);
            bo.setChainName(name);
            bo.setCmpProperty(tree);
            bo.setInputParams(parseInputParams(inputParamsJson));
            bo.setLogLevel(level);
            bo.setRemark(trimToNull(note));
            // 无登录态时 InjectionMetaObjectHandler 会把 create_by 填成 -1，这里显式落发起人
            bo.setCreateBy(context.userId());
            if (!Boolean.TRUE.equals(chainService.insertByBo(bo))) {
                throw new IllegalStateException("新建链路失败");
            }
            chainId = bo.getId();
            // 记一次新建快照（operation=CREATE），回滚该轮即删除这条链
            snapshotService.recordCreate(context.runId(), round, chainId, code, name);
        } else {
            DatabusChainVo before = chainService.queryById(draftId);
            if (before == null) {
                throw new IllegalArgumentException("链路 " + draftId + " 不存在或已删除");
            }
            if (OWNER_SYSTEM.equals(before.getOwnerType())) {
                throw new IllegalArgumentException("系统链路只能由治理角色在链路管理页修改");
            }
            if (!STATUS_DRAFT.equals(before.getStatus())) {
                throw new IllegalArgumentException("链路当前状态为 " + before.getStatus()
                    + "，AI 只能修改草稿态链路");
            }
            if (!Objects.equals(before.getCreateBy(), context.userId())) {
                throw new IllegalArgumentException("只能修改自己创建的链路");
            }
            // 同链并发：读取之后被别人改过且用户尚未确认覆盖 → 不写库，把问题抛回给模型问用户
            String currentUpdateTime = formatTime(before.getUpdateTime());
            String expected = trimToNull(expectedUpdateTime);
            if (expected != null && currentUpdateTime != null && !expected.equals(currentUpdateTime)) {
                if (!Boolean.TRUE.equals(force)) {
                    Map<String, Object> conflict = new LinkedHashMap<>();
                    conflict.put("conflict", true);
                    conflict.put("message", "这条链路在你读取之后被修改过，要继续覆盖吗？");
                    conflict.put("expectedUpdateTime", expected);
                    conflict.put("currentUpdateTime", currentUpdateTime);
                    return conflict;
                }
                overwritten = true;
            }
            // 动手前先落快照，失败也能回到这一轮之前
            int round = agentGuard.nextRound(context.runId());
            snapshotService.record(context.runId(), round, before);

            name = trimToNull(chainName) == null ? before.getChainName() : chainName.trim();
            List<ChainInputParam> params = parseInputParams(inputParamsJson);
            DatabusChainBo bo = new DatabusChainBo();
            bo.setId(draftId);
            bo.setChainCode(trimToNull(chainCode) == null ? before.getChainCode() : chainCode.trim());
            bo.setChainName(name);
            bo.setCmpProperty(tree);
            // inputParams 省略时沿用原值；显式传 [] 才是清空
            bo.setInputParams(params == null ? before.getInputParams() : params);
            bo.setLogLevel(level);
            if (trimToNull(note) != null) {
                bo.setRemark(note.trim());
            }
            bo.setUpdateBy(context.userId());
            if (!Boolean.TRUE.equals(chainService.updateByBo(bo, context.userId()))) {
                throw new IllegalStateException("保存链路失败");
            }
            chainId = draftId;
        }

        // 回读落库后的更新时间，前端存为画布基线，用于发现漏掉的后续推送
        DatabusChainVo saved = chainService.queryById(chainId);
        String updateTime = saved == null ? null : formatTime(saved.getUpdateTime());
        eventPublisher.publishChainCanvas(context, chainId, name, tree, elExpression, null, created, updateTime);
        eventPublisher.publishChainDraft(context, chainId, name);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("draftId", chainId);
        payload.put("chainName", name);
        payload.put("elExpression", elExpression);
        payload.put("valid", true);
        payload.put("verifyMessage", null);
        payload.put("nodeCount", nodeCount);
        payload.put("scriptNodeCount", 0);
        if (overwritten) {
            payload.put("warning", "已覆盖他人改动");
        }
        return payload;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }

    private static CmpProperty parseTree(String json) {
        String text = trimToNull(json);
        if (text == null) {
            throw new IllegalArgumentException("cmpProperty 不能为空，请给出画布结构树 JSON");
        }
        CmpProperty tree;
        try {
            tree = JsonUtils.parseObject(text, CmpProperty.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("cmpProperty 不是合法的结构树 JSON：" + e.getMessage());
        }
        if (tree == null) {
            throw new IllegalArgumentException("cmpProperty 解析结果为空");
        }
        return tree;
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

    private static String normalizeLogLevel(String logLevel) {
        String value = trimToNull(logLevel);
        if (value == null) {
            return LOG_LEVEL_FULL;
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (!"OFF".equals(upper) && !"BASIC".equals(upper) && !LOG_LEVEL_FULL.equals(upper)) {
            throw new IllegalArgumentException("logLevel 不合法，可选值：OFF / BASIC / FULL");
        }
        return upper;
    }

    private static int countNodes(CmpProperty node) {
        if (node == null) {
            return 0;
        }
        int total = trimToNull(node.getId()) == null ? 0 : 1;
        if (node.getCondition() != null) {
            total += countNodes(node.getCondition());
        }
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                total += countNodes(child);
            }
        }
        return total;
    }
}
