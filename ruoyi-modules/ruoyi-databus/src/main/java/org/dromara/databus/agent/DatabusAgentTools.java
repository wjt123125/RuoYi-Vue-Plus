package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.ComponentOptionVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.el.bean.ChainInputParam;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.executor.DatabusExecutor;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Databus 工具集：切片 2「读物料」（只读检索）+ 切片 3「改画布」（{@code databus_chain_save}）。
 * <p>
 * 写工具也不拼 EL、不写 canvasData：树交给 {@code IDatabusChainService} 生成 EL，画布交给前端按
 * cmpProperty 全量重画；每次动手前由 {@code IDatabusAgentSnapshotService} 落快照，回滚只认服务端快照。
 * <p>
 * 工具由 SnailAI Server 经 gRPC 派回本进程执行，身份一律由 {@link AgentGuard}
 * 用 conversationId 反查 run 得到，不接受模型传入的身份参数；每次调用都经
 * {@link AgentStepRecorder} 落 databus_agent_step。
 * <p>
 * 工具异常一律转 JSON 错误串返回（抛异常会被包装成 ToolExecutionException，
 * 模型拿不到可读原因，也不利于自纠错）。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabusAgentTools {

    /**
     * 只读检索所需权限点：与工作台链路列表 / 物料面板 /options 一致。
     */
    private static final String PERMISSION_READ = "databus:editor:list";
    /**
     * 修改草稿链路所需权限点，与 DatabusChainController 的 PUT 一致。
     */
    private static final String PERMISSION_WRITE = "databus:editor:edit";
    /**
     * 新建草稿链路所需权限点，与 DatabusChainController 的 POST 一致。
     */
    private static final String PERMISSION_ADD = "databus:editor:add";

    private static final String TOOL_CHAIN_SEARCH = "databus_chain_search";
    private static final String TOOL_CHAIN_GET = "databus_chain_get";
    private static final String TOOL_COMPONENT_SEARCH = "databus_component_search";
    private static final String TOOL_COMPONENT_GET = "databus_component_get";
    private static final String TOOL_CHAIN_SAVE = "databus_chain_save";

    /**
     * AI 只能改草稿态链路，已发布 / 已下线的一律拒绝（避免改了正在跑的链路）。
     */
    private static final String STATUS_DRAFT = "DRAFT";
    private static final String OWNER_SYSTEM = "SYSTEM";
    private static final String LOG_LEVEL_FULL = "FULL";
    /**
     * 模板链路标记（databus_chain.is_template）：模板对所有人生效，非模板只认创建人。
     */
    private static final String TEMPLATE_FLAG_YES = "1";

    /**
     * component_get 的返回裁剪段：元信息 / 配置项定义 / 脚本源码。
     */
    private static final String FIELD_META = "meta";
    private static final String FIELD_SCHEMA = "schema";
    private static final String FIELD_SCRIPT = "script";

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 20;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentGuard agentGuard;
    private final AgentStepRecorder stepRecorder;
    private final IDatabusChainService chainService;
    private final IDatabusComponentService componentService;
    private final IDatabusAgentSnapshotService snapshotService;
    private final AgentEventPublisher eventPublisher;
    private final DatabusExecutor databusExecutor;
    private final ExpressGenerator expressGenerator;

    /**
     * 按关键词检索当前用户自己的链路（只读）。
     *
     * @param keyword  链路名称或编码片段，留空按最近更新列出
     * @param pageSize 本页条数
     * @param cursor   上一页游标
     * @return JSON 结果
     */
    @Tool(name = TOOL_CHAIN_SEARCH, description = "检索当前用户自己的链路（只读），用于找现成链路做参考。"
        + "返回 id、编码、名称、状态、版本、更新时间等摘要，不含画布与 EL。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String chainSearch(
        @ToolParam(description = "链路名称或编码的关键词片段；留空按最近更新列出", required = false) String keyword,
        @ToolParam(description = "本页条数，默认 10，最大 20", required = false) Integer pageSize,
        @ToolParam(description = "上一页返回的 nextCursor，首页不传", required = false) String cursor) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("pageSize", pageSize);
        args.put("cursor", cursor);
        return execute(TOOL_CHAIN_SEARCH, args, ctx -> doChainSearch(ctx, keyword, pageSize, cursor));
    }

    /**
     * 检索物料面板里可用的原子物料（只读）。
     *
     * @param keyword  关键词
     * @param tags     标签，逗号分隔
     * @param nodeType 节点类型
     * @param pageSize 本页条数
     * @param cursor   上一页游标
     * @return JSON 结果
     */
    @Tool(name = TOOL_COMPONENT_SEARCH, description = "检索物料面板里可用的原子物料（只读），用于先找现成物料再决定要不要新做。"
        + "必须先给 keyword / tags / nodeType 至少一个过滤条件，禁止无过滤全量拉取。"
        + "返回 code、名称、分组、来源、节点类型、标签等摘要，不含完整 schema；"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String componentSearch(
        @ToolParam(description = "匹配 code / 名称 / 短名 / 描述 / 标签 的关键词片段", required = false) String keyword,
        @ToolParam(description = "标签过滤，多个用逗号分隔，命中任意一个即可", required = false) String tags,
        @ToolParam(description = "LiteFlow 节点类型过滤：NODE / BOOLEAN / FOR / SWITCH", required = false) String nodeType,
        @ToolParam(description = "本页条数，默认 10，最大 20", required = false) Integer pageSize,
        @ToolParam(description = "上一页返回的 nextCursor，首页不传", required = false) String cursor) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("tags", tags);
        args.put("nodeType", nodeType);
        args.put("pageSize", pageSize);
        args.put("cursor", cursor);
        return execute(TOOL_COMPONENT_SEARCH, args,
            ctx -> doComponentSearch(keyword, tags, nodeType, pageSize, cursor));
    }

    /**
     * 拿一条链路的整条记录（只读）。
     *
     * @param chainId 链路 id
     * @return JSON 结果
     */
    @Tool(name = TOOL_CHAIN_GET, description = "拿一条链路的整条记录（只读）：画布结构树 cmpProperty、入参 inputParams、logLevel、编码/名称/状态/版本/归属。"
        + "改现有链路前必须先调它看现状，结构树直接复用，不要凭记忆拼。只能读自己创建的链路或模板链路。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String chainGet(@ToolParam(description = "链路 id") Long chainId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chainId", chainId);
        return execute(TOOL_CHAIN_GET, args, ctx -> doChainGet(ctx, chainId));
    }

    /**
     * 拿单个组件的整条记录（只读）。
     *
     * @param componentCode 组件编码
     * @param componentId   组件主键
     * @param fields        裁剪返回段
     * @return JSON 结果
     */
    @Tool(name = TOOL_COMPONENT_GET, description = "拿单个组件的整条记录（只读）：元信息、nodeType、paramSchema 配置项定义、脚本源码、版本与状态。"
        + "拼树前必须先调它确认入参定义，不许凭猜填参数。componentCode 与 componentId 二选一。"
        + "fields 可裁剪返回：meta（元信息与状态）/ schema（配置项定义）/ script（脚本源码），逗号分隔，默认全给。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String componentGet(
        @ToolParam(description = "组件编码", required = false) String componentCode,
        @ToolParam(description = "组件主键，与 componentCode 二选一", required = false) Long componentId,
        @ToolParam(description = "裁剪返回段：meta/schema/script，逗号分隔，默认全给", required = false) String fields) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("componentCode", componentCode);
        args.put("componentId", componentId);
        args.put("fields", fields);
        return execute(TOOL_COMPONENT_GET, args, ctx -> doComponentGet(componentCode, componentId, fields));
    }

    /**
     * 把 AI 拼好的链路结构存成草稿（status=DRAFT）。
     * <p>
     * EL 一律由 {@link ExpressGenerator} 生成并校验，工具层不自己拼；内联脚本节点一律拒绝
     * （必须先落脚本草稿、再把节点换成组件引用）。每次动手前把改之前的整条链路存
     * {@code databus_agent_snapshot}，供「回到这次对话前」按 run 整体回滚。
     *
     * @param draftId           草稿链路 id；有就是改，没有就是新建
     * @param chainCode         链路编码，新建必填
     * @param chainName         链路名称
     * @param baseChainId       照着哪条链路改（只作参考，保存以 cmpProperty 为唯一依据）
     * @param cmpProperty       画布结构树 JSON（唯一依据）
     * @param inputParams       链路入参登记表 JSON 数组，可空
     * @param logLevel          执行记录档位 OFF/BASIC/FULL，默认 FULL
     * @param note              备注
     * @param expectedUpdateTime 读取时链路的 updateTime，保存时原样回传（乐观锁期望值）
     * @param force             用户已确认覆盖他人改动
     * @return JSON 结果
     */
    @Tool(name = TOOL_CHAIN_SAVE, description = "把拼好的链路结构存成草稿（status=DRAFT），新建与修改都走这里。"
        + "同一会话可以多次新建或修改：新建不传 draftId，修改传 draftId。"
        + "cmpProperty 是画布结构树 JSON，是保存的唯一依据；EL 由服务端生成并校验，不要自己拼 EL。"
        + "链路里含内联脚本节点会被直接拒绝，必须先调 databus_script_draft_save 落脚本草稿、再把节点换成组件引用。"
        + "修改已发布/已下线的链路、系统链路、别人的链路都会被拒绝。"
        + "修改前必须先调 databus_chain_get 拿到 updateTime，原样作为 expectedUpdateTime 传回；"
        + "若链路在你读取之后被别人改过，会返回 conflict=true 且不写库，此时把 message 转述给用户，"
        + "用户明确同意覆盖后再带 force=true 重试。身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String chainSave(
        @ToolParam(description = "草稿链路 id；有就是改，没有就是新建", required = false) Long draftId,
        @ToolParam(description = "链路编码，新建必填（字母/数字/下划线）", required = false) String chainCode,
        @ToolParam(description = "链路名称；修改时可省略，沿用原名称", required = false) String chainName,
        @ToolParam(description = "照着哪条链路改（只作参考，保存以 cmpProperty 为准）", required = false) Long baseChainId,
        @ToolParam(description = "画布结构树 JSON（唯一依据），根节点通常是 THEN") String cmpProperty,
        @ToolParam(description = "链路入参登记表 JSON 数组，可空；修改时传 [] 表示清空", required = false) String inputParams,
        @ToolParam(description = "执行记录档位 OFF / BASIC / FULL，默认 FULL", required = false) String logLevel,
        @ToolParam(description = "备注", required = false) String note,
        @ToolParam(description = "读取时 databus_chain_get 返回的 updateTime，原样传回", required = false) String expectedUpdateTime,
        @ToolParam(description = "用户已确认覆盖他人改动后才传 true", required = false) Boolean force) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("draftId", draftId);
        args.put("chainCode", chainCode);
        args.put("chainName", chainName);
        args.put("baseChainId", baseChainId);
        args.put("cmpProperty", cmpProperty);
        args.put("inputParams", inputParams);
        args.put("logLevel", logLevel);
        args.put("note", note);
        args.put("expectedUpdateTime", expectedUpdateTime);
        args.put("force", force);
        String permission = draftId == null ? PERMISSION_ADD : PERMISSION_WRITE;
        return execute(TOOL_CHAIN_SAVE, args, permission,
            ctx -> doChainSave(ctx, draftId, chainCode, chainName, cmpProperty, inputParams, logLevel, note,
                expectedUpdateTime, force));
    }

    /**
     * 统一执行壳：守卫 → 业务 → 审计。守卫拒绝与执行失败都转成 JSON 错误串。
     *
     * @param tool 工具名
     * @param args 入参快照（审计用）
     * @param body 业务体
     * @return JSON 结果
     */
    private String execute(String tool, Map<String, Object> args, Function<AgentRunContext, Map<String, Object>> body) {
        return execute(tool, args, PERMISSION_READ, body);
    }

    private String execute(String tool, Map<String, Object> args, String permission,
                           Function<AgentRunContext, Map<String, Object>> body) {
        long began = System.currentTimeMillis();
        String argsJson = JsonUtils.toJsonString(args);
        AgentRunContext context = null;
        try {
            context = agentGuard.check(permission);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", true);
            payload.putAll(body.apply(context));
            String result = JsonUtils.toJsonString(payload);
            stepRecorder.recordSuccess(context, tool, argsJson, result, System.currentTimeMillis() - began);
            return result;
        } catch (AgentGuardRejectedException e) {
            String result = errorJson(e.getMessage());
            stepRecorder.recordReject(e.getRunId(), tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            return result;
        } catch (Exception e) {
            log.warn("[databus-agent] 工具 {} 执行失败", tool, e);
            String result = errorJson("工具执行失败：" + e.getMessage());
            stepRecorder.recordFailure(context, tool, argsJson, result, e.getMessage(), System.currentTimeMillis() - began);
            return result;
        }
    }

    private Map<String, Object> doChainSave(AgentRunContext context, Long draftId, String chainCode,
                                            String chainName, String cmpPropertyJson, String inputParamsJson,
                                            String logLevel, String note, String expectedUpdateTime, Boolean force) {
        CmpProperty tree = parseTree(cmpPropertyJson);
        // 内联脚本拦截：脚本只允许走 databus_script_draft_save，链路树里出现脚本叶子一律不写库
        List<DatabusExecutor.ScriptNodeSpec> scripts = databusExecutor.collectScriptNodes(tree);
        if (!scripts.isEmpty()) {
            throw new IllegalArgumentException("本链路含 " + scripts.size()
                + " 个内联脚本节点，必须先调 databus_script_draft_save 落草稿、再把节点换成组件引用");
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

        eventPublisher.publishCanvas(context, chainId, name, tree, elExpression, null, created);
        eventPublisher.publishDraft(context, chainId, name);

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

    private Map<String, Object> doChainSearch(AgentRunContext context, String keyword, Integer pageSize, String cursor) {
        int size = normalizePageSize(pageSize);
        int offset = parseCursor(cursor);
        String kw = trimToNull(keyword);

        DatabusChainBo bo = new DatabusChainBo();
        bo.setOnlyMine(true);
        bo.setChainName(kw);
        PageResult<DatabusChainVo> page = chainService.queryPageList(bo, pageQuery(offset, size), context.userId());
        if (page.getTotal() == 0 && kw != null) {
            // 名称没命中时再按编码找一次（BO 两字段同时设会 AND 变窄）
            bo.setChainName(null);
            bo.setChainCode(kw);
            page = chainService.queryPageList(bo, pageQuery(offset, size), context.userId());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (DatabusChainVo vo : page.getRows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", vo.getId());
            item.put("code", vo.getChainCode());
            item.put("name", vo.getChainName());
            item.put("status", vo.getStatus());
            item.put("version", vo.getVersion());
            item.put("isTemplate", vo.getIsTemplate());
            item.put("remark", vo.getRemark());
            item.put("updateTime", formatTime(vo.getUpdateTime()));
            items.add(item);
        }
        return pagePayload(page.getTotal(), items, offset, size);
    }

    private Map<String, Object> doComponentSearch(String keyword, String tags, String nodeType,
                                                  Integer pageSize, String cursor) {
        String kw = trimToNull(keyword);
        List<String> tagList = splitTags(tags);
        NodeTypeKind type = parseNodeType(nodeType);
        if (kw == null && tagList.isEmpty() && type == null) {
            throw new IllegalArgumentException("至少提供 keyword / tags / nodeType 之一，禁止无过滤全量拉取");
        }
        int size = normalizePageSize(pageSize);
        int offset = parseCursor(cursor);

        List<ComponentOptionVo> matched = new ArrayList<>();
        for (ComponentOptionVo cmp : componentService.queryOptions().components()) {
            if (matchesComponent(cmp, kw, tagList, type)) {
                matched.add(cmp);
            }
        }
        if (kw != null) {
            matched.sort(Comparator.comparingInt((ComponentOptionVo cmp) -> relevance(cmp, kw))
                .thenComparing(cmp -> cmp.code() == null ? "" : cmp.code()));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = offset; i < matched.size() && items.size() < size; i++) {
            ComponentOptionVo cmp = matched.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", cmp.code());
            item.put("name", cmp.name());
            item.put("shortName", cmp.shortName());
            item.put("group", cmp.group());
            item.put("domain", cmp.domain());
            item.put("description", cmp.description());
            item.put("nodeType", cmp.nodeType() == null ? null : cmp.nodeType().name());
            item.put("editor", cmp.editor() == null ? null : cmp.editor().jsonValue());
            item.put("source", cmp.source() == null ? null : cmp.source().name());
            item.put("tags", cmp.tags());
            item.put("deprecated", cmp.deprecated());
            item.put("deprecateNote", cmp.deprecateNote());
            items.add(item);
        }
        return pagePayload(matched.size(), items, offset, size);
    }

    private Map<String, Object> doChainGet(AgentRunContext context, Long chainId) {
        if (chainId == null) {
            throw new IllegalArgumentException("chainId 不能为空");
        }
        DatabusChainVo chain = chainService.queryById(chainId);
        if (chain == null) {
            throw new IllegalArgumentException("链路 " + chainId + " 不存在或已删除");
        }
        // 非模板链路只认创建人：别人的链路不给看结构树
        if (!TEMPLATE_FLAG_YES.equals(chain.getIsTemplate())
            && !Objects.equals(chain.getCreateBy(), context.userId())) {
            throw new IllegalArgumentException("只能读取自己创建的链路或模板链路");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", chain.getId());
        payload.put("code", chain.getChainCode());
        payload.put("name", chain.getChainName());
        payload.put("status", chain.getStatus());
        payload.put("version", chain.getVersion());
        payload.put("ownerType", chain.getOwnerType());
        payload.put("isTemplate", chain.getIsTemplate());
        payload.put("logLevel", chain.getLogLevel());
        payload.put("remark", chain.getRemark());
        payload.put("elExpression", chain.getElExpression());
        payload.put("inputParams", chain.getInputParams());
        // 结构树是 AI 改链路的唯一依据，必须整棵给全
        payload.put("cmpProperty", chain.getCmpProperty());
        payload.put("updateTime", formatTime(chain.getUpdateTime()));
        return payload;
    }

    private Map<String, Object> doComponentGet(String componentCode, Long componentId, String fields) {
        String code = trimToNull(componentCode);
        if (componentId == null && code == null) {
            throw new IllegalArgumentException("componentCode 与 componentId 至少给一个");
        }

        DatabusComponentVo cmp;
        if (componentId != null) {
            cmp = componentService.queryById(componentId);
        } else {
            DatabusComponentBo bo = new DatabusComponentBo();
            bo.setComponentCode(code);
            PageResult<DatabusComponentVo> page = componentService.queryPageList(bo, pageQuery(0, MAX_PAGE_SIZE));
            cmp = page.getRows().stream()
                .filter(row -> row.getComponentCode() != null && row.getComponentCode().equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
        }
        if (cmp == null) {
            throw new IllegalArgumentException("组件不存在：" + (code == null ? componentId : code)
                + "（草稿态组件在采纳前查不到）");
        }

        List<String> parts = splitFields(fields);
        Map<String, Object> payload = new LinkedHashMap<>();
        if (parts.contains(FIELD_META)) {
            payload.put("id", cmp.getId());
            payload.put("code", cmp.getComponentCode());
            payload.put("name", cmp.getComponentName());
            payload.put("shortName", cmp.getShortName());
            payload.put("group", cmp.getGroupName());
            payload.put("category", cmp.getCategory());
            payload.put("domain", cmp.getDomain());
            payload.put("description", cmp.getDescription());
            payload.put("nodeType", cmp.getNodeType());
            payload.put("editor", cmp.getEditor());
            payload.put("tags", cmp.getTags());
            payload.put("status", cmp.getStatus());
            payload.put("version", cmp.getVersion());
            payload.put("scriptLang", cmp.getScriptLang());
            payload.put("deprecated", cmp.getDeprecated());
            payload.put("deprecateNote", cmp.getDeprecateNote());
            payload.put("docUrl", cmp.getDocUrl());
            payload.put("remark", cmp.getRemark());
        }
        if (parts.contains(FIELD_SCHEMA)) {
            payload.put("paramSchema", parseJsonQuietly(cmp.getParamSchema()));
            payload.put("dataExample", parseJsonQuietly(cmp.getDataExample()));
            payload.put("inputSchema", cmp.getInputSchema());
            payload.put("outputSchema", cmp.getOutputSchema());
        }
        if (parts.contains(FIELD_SCRIPT)) {
            payload.put("scriptBody", cmp.getScriptBody());
        }
        return payload;
    }

    private static Map<String, Object> pagePayload(long total, List<Map<String, Object>> items, int offset, int size) {
        int nextOffset = offset + items.size();
        boolean hasMore = nextOffset < total;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCount", total);
        payload.put("returned", items.size());
        payload.put("items", items);
        payload.put("nextCursor", hasMore ? String.valueOf(nextOffset) : null);
        payload.put("truncated", hasMore);
        return payload;
    }

    private static boolean matchesComponent(ComponentOptionVo cmp, String keyword, List<String> tags, NodeTypeKind type) {
        if (type != null && cmp.nodeType() != type) {
            return false;
        }
        if (!tags.isEmpty()) {
            boolean tagHit = cmp.tags() != null && cmp.tags().stream()
                .anyMatch(tag -> tag != null && tags.contains(tag.toLowerCase(Locale.ROOT)));
            if (!tagHit) {
                return false;
            }
        }
        if (keyword == null) {
            return true;
        }
        return containsIgnoreCase(cmp.code(), keyword)
            || containsIgnoreCase(cmp.name(), keyword)
            || containsIgnoreCase(cmp.shortName(), keyword)
            || containsIgnoreCase(cmp.description(), keyword)
            || (cmp.tags() != null && cmp.tags().stream().anyMatch(tag -> containsIgnoreCase(tag, keyword)));
    }

    private static int relevance(ComponentOptionVo cmp, String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT);
        String code = cmp.code() == null ? "" : cmp.code().toLowerCase(Locale.ROOT);
        if (code.equals(k)) {
            return 0;
        }
        if (code.startsWith(k)) {
            return 1;
        }
        String name = cmp.name() == null ? "" : cmp.name().toLowerCase(Locale.ROOT);
        return name.contains(k) ? 2 : 3;
    }

    private static boolean containsIgnoreCase(String text, String keyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static NodeTypeKind parseNodeType(String nodeType) {
        String value = trimToNull(nodeType);
        if (value == null) {
            return null;
        }
        try {
            return NodeTypeKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("nodeType 不合法，可选值：NODE / BOOLEAN / FOR / SWITCH");
        }
    }

    private static PageQuery pageQuery(int offset, int size) {
        return new PageQuery(size, offset / size + 1);
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static int parseCursor(String cursor) {
        String value = trimToNull(cursor);
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(value), 0);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor 必须是上一页返回的 nextCursor 数字");
        }
    }

    private static String trimToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }

    /**
     * 解析 fields 参数：空值或全是非法值都按「全给」处理。
     */
    private static List<String> splitFields(String fields) {
        List<String> all = List.of(FIELD_META, FIELD_SCHEMA, FIELD_SCRIPT);
        String text = trimToNull(fields);
        if (text == null) {
            return all;
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split(",")) {
            String item = part.trim().toLowerCase(Locale.ROOT);
            if (all.contains(item) && !parts.contains(item)) {
                parts.add(item);
            }
        }
        return parts.isEmpty() ? all : parts;
    }

    /**
     * paramSchema / dataExample 本身是 JSON 串，解析失败就原样返回，别让整条记录读不出来。
     */
    private static Object parseJsonQuietly(String json) {
        String text = trimToNull(json);
        if (text == null) {
            return null;
        }
        try {
            return JsonUtils.parseMap(text);
        } catch (Exception e) {
            return text;
        }
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

    private static String errorJson(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message == null || message.isBlank() ? "未知错误" : message);
        return JsonUtils.toJsonString(payload);
    }

}