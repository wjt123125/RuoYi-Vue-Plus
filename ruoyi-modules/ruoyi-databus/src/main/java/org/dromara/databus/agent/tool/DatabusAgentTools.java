package org.dromara.databus.agent.tool;

import org.dromara.databus.agent.run.AgentStepRecorder;
import org.dromara.databus.agent.run.AgentRunContext;
import org.dromara.databus.agent.event.AgentCardChannel;
import org.dromara.databus.agent.event.AgentCard;
import org.dromara.databus.agent.support.AgentGuard;
import lombok.RequiredArgsConstructor;
import static org.dromara.databus.agent.tool.AgentToolText.trimToNull;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.dromara.databus.constant.AgentConstants.MAX_CARD_OPTIONS;
import static org.dromara.databus.constant.AgentConstants.MAX_PAGE_SIZE;
import static org.dromara.databus.constant.AgentConstants.PERMISSION_ADD;
import static org.dromara.databus.constant.AgentConstants.PERMISSION_RUN;
import static org.dromara.databus.constant.AgentConstants.PERMISSION_WRITE;
import static org.dromara.databus.constant.AgentConstants.TOOL_ASK_USER;
import static org.dromara.databus.constant.AgentConstants.TOOL_CHAIN_DRAFT_SAVE;
import static org.dromara.databus.constant.AgentConstants.TOOL_CHAIN_GET;
import static org.dromara.databus.constant.AgentConstants.TOOL_CHAIN_RUN;
import static org.dromara.databus.constant.AgentConstants.TOOL_CHAIN_SEARCH;
import static org.dromara.databus.constant.AgentConstants.TOOL_COMPONENT_DRAFT_SAVE;
import static org.dromara.databus.constant.AgentConstants.TOOL_COMPONENT_GET;
import static org.dromara.databus.constant.AgentConstants.TOOL_COMPONENT_SEARCH;
import static org.dromara.databus.constant.AgentConstants.TOOL_CONNECTION_SEARCH;
import static org.dromara.databus.constant.AgentConstants.TOOL_CONNECTOR_SEARCH;
import static org.dromara.databus.constant.AgentConstants.TOOL_DB_SCHEMA_SEARCH;
import static org.dromara.databus.constant.CommonConstants.STATUS_DRAFT;
import static org.dromara.databus.constant.ComponentConstants.FIELD_META;
import static org.dromara.databus.constant.ComponentConstants.FIELD_SCHEMA;
import static org.dromara.databus.constant.ComponentConstants.FIELD_SCRIPT;

/**
 * Databus 工具集：切片 2「读物料」（只读检索）+ 切片 3「改画布」（{@code databus_chain_draft_save}）
 * + 切片 6「试跑」（{@code databus_chain_run}）。
 * <p>
 * 写工具也不拼 EL、不写 canvasData：树交给 {@code IDatabusChainService} 生成 EL，画布交给前端按
 * cmpProperty 全量重画；每次动手前由 {@code IDatabusAgentSnapshotService} 落快照，回滚只认服务端快照。
 * <p>
 * 试跑工具直接复用 {@link org.dromara.databus.service.IDatabusPreviewRunService}（与预览页同一套六步），只回摘要与节点级错误，
 * 不写 databus_execution、不落快照、不占纠错轮数。
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
@Component
@RequiredArgsConstructor
public class DatabusAgentTools {

    private final IDatabusComponentService componentService;
    private final AgentCardChannel cardChannel;
    private final AgentToolDispatcher toolExecution;
    private final AgentQueryToolSupport searchSupport;
    private final AgentChainDraftToolSupport chainDraftSupport;
    private final AgentChainRunToolSupport chainRunSupport;
    private final AgentComponentDraftToolSupport componentDraftSupport;

    /**
     * 按关键词检索当前用户自己的链路（只读）。
     *
     * @param keyword  链路名称或编码片段，留空按最近更新列出
     * @param pageSize 本页条数
     * @param cursor   上一页游标
     * @return JSON 结果
     */
    @AgentToolStep(title = "检索链路", target = "keyword", fallback = "最近更新")
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
        return toolExecution.execute(TOOL_CHAIN_SEARCH, args,
            ctx -> searchSupport.doChainSearch(ctx, keyword, pageSize, cursor));
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
    @AgentToolStep(title = "检索物料", target = "keyword")
    @Tool(name = TOOL_COMPONENT_SEARCH, description = "检索物料面板里可用的原子物料（只读），用于先找现成物料再决定要不要新做。"
        + "必须先给 keyword / tags / nodeType 至少一个过滤条件，禁止无过滤全量拉取。"
        + "返回 code、名称、分组、来源、节点类型、标签等摘要，不含完整 schema；"
        + "摘要里的 needsConnector 标识该组件是否要配连接，为 true 时先调 databus_connection_search 拿 connectionId；"
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
        return toolExecution.execute(TOOL_COMPONENT_SEARCH, args,
            ctx -> searchSupport.doComponentSearch(keyword, tags, nodeType, pageSize, cursor));
    }

    /**
     * 检索可用的数据连接（只读）。
     *
     * @param keyword         匹配 connectionId / connectionName 的关键词
     * @param connectorFamily 连接器族精确过滤
     * @param pageSize        本页条数
     * @param cursor          上一页游标
     * @return JSON 结果
     */
    @AgentToolStep(title = "检索数据连接", target = "keyword")
    @Tool(name = TOOL_CONNECTION_SEARCH, description = "检索可用的数据连接（只读），配任何 CONNECTION_SELECT 连接字段前必须先调它拿 connectionId，不许猜。"
        + "只列已启用连接，返回 connectionId、connectionName、connectorType、connectorFamily 四个安全字段，不含配置与凭证。"
        + "keyword 与 connectorFamily 都可留空，留空即列出全部可用连接；connectorFamily 按连接器族精确过滤（如 jdbc）。"
        + "老链路上的连接若查不到，说明已停用或连接器未装载，不要照抄老链路。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String connectionSearch(
        @ToolParam(description = "匹配 connectionId / connectionName 的关键词片段，留空列出全部", required = false) String keyword,
        @ToolParam(description = "连接器族精确过滤（如 jdbc），留空不过滤", required = false) String connectorFamily,
        @ToolParam(description = "本页条数，默认 10，最大 20", required = false) Integer pageSize,
        @ToolParam(description = "上一页返回的 nextCursor，首页不传", required = false) String cursor) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        args.put("connectorFamily", connectorFamily);
        args.put("pageSize", pageSize);
        args.put("cursor", cursor);
        return toolExecution.execute(TOOL_CONNECTION_SEARCH, args,
            ctx -> searchSupport.doConnectionSearch(keyword, connectorFamily, pageSize, cursor));
    }

    /**
     * 检索平台已装载的连接器类型（只读），用于「查不到连接时说清该建什么」。
     *
     * @param keyword 匹配 type / label / family 的关键词
     * @return JSON 结果
     */
    @AgentToolStep(title = "检索连接器类型", target = "keyword")
    @Tool(name = TOOL_CONNECTOR_SEARCH, description = "检索平台已装载的连接器类型（只读），回答「该建什么连接」：给 type、label、能力族 family、"
        + "是否支持 SQL（sqlCapable）、操作清单与配置字段（字段名/标签/类型/必填/是否敏感）。"
        + "只回字段定义，不回任何真实配置值；required=true 且 sensitive=true 的字段需要人准备凭据，必须由人在连接页填写。"
        + "connection_search 查不到可用连接时，先调本工具说清该装/该建哪种连接器，不要自己编造连接器类型。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String connectorSearch(
        @ToolParam(description = "匹配连接器 type / label / family 的关键词片段，留空列出全部", required = false) String keyword) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("keyword", keyword);
        return toolExecution.execute(TOOL_CONNECTOR_SEARCH, args, ctx -> searchSupport.doConnectorSearch(keyword));
    }

    /**
     * 查看某个数据连接背后的库表与字段（只读），是写 SQL 与造组件前的数据依据。
     *
     * @param connectionId connection_search 返回的连接标识
     * @param keyword      表名/表注释/字段名关键词
     * @param pageSize     本页表数量
     * @param cursor       上一页游标
     * @return JSON 结果
     */
    @AgentToolStep(title = "查看库表结构", target = "keyword")
    @Tool(name = TOOL_DB_SCHEMA_SEARCH, description = "查看某个数据连接背后的库表与字段（只读）：返回表名、表注释、字段名、字段类型、是否可空、是否主键、字段注释。"
        + "connectionId 必须来自 databus_connection_search，不许猜；写 SQL、造数据类组件之前必须先调它看真实结构，不许臆造表名与字段。"
        + "keyword 可匹配表名、表注释或字段名（字段名命中时反查所属表）；留空按表名顺序列前 N 张表。"
        + "当前只支持 MySQL / PostgreSQL 连接器；其他连接器会明确报错，此时请让用户直接提供表与字段。"
        + "只回结构，不回任何业务数据行。身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String dbSchemaSearch(
        @ToolParam(description = "databus_connection_search 返回的 connectionId，必填") String connectionId,
        @ToolParam(description = "表名 / 表注释 / 字段名的关键词片段，留空列出前 N 张表", required = false) String keyword,
        @ToolParam(description = "本页表数量，默认 10，最大 20", required = false) Integer pageSize,
        @ToolParam(description = "上一页返回的 nextCursor，首页不传", required = false) String cursor) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("connectionId", connectionId);
        args.put("keyword", keyword);
        args.put("pageSize", pageSize);
        args.put("cursor", cursor);
        return toolExecution.execute(TOOL_DB_SCHEMA_SEARCH, args,
            ctx -> searchSupport.doDbSchemaSearch(connectionId, keyword, pageSize, cursor));
    }

    /**
     * 拿一条链路的整条记录（只读）。
     *
     * @param chainId 链路 id
     * @return JSON 结果
     */
    @AgentToolStep(title = "读取链路")
    @Tool(name = TOOL_CHAIN_GET, description = "拿一条链路的整条记录（只读）：画布结构树 cmpProperty、入参 inputParams、logLevel、编码/名称/状态/版本/归属。"
        + "改现有链路前必须先调它看现状，结构树直接复用，不要凭记忆拼。只能读自己创建的链路或模板链路。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String chainGet(@ToolParam(description = "链路 id") Long chainId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chainId", chainId);
        return toolExecution.execute(TOOL_CHAIN_GET, args, ctx -> searchSupport.doChainGet(ctx, chainId));
    }

    /**
     * 拿单个组件的整条记录（只读）。
     *
     * @param componentCode 组件编码
     * @param componentId   组件主键
     * @param fields        裁剪返回段
     * @return JSON 结果
     */
    @AgentToolStep(title = "读取组件", target = "componentCode")
    @Tool(name = TOOL_COMPONENT_GET, description = "拿单个组件的整条记录（只读）：元信息、nodeType、paramSchema 配置项定义、脚本源码、版本与状态。"
        + "拼树前必须先调它确认入参定义，不许凭猜填参数。componentCode 与 componentId 二选一。"
        + "fields 可裁剪返回：meta（元信息与状态）/ schema（配置项定义）/ script（脚本源码），逗号分隔，默认全给。"
        + "也支持读自己本次的组件草稿（status=DRAFT，仅限本人），meta 里 draft=true 表示尚未生效、还不能拼进链路。"
        + "身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String componentGet(
        @ToolParam(description = "组件编码", required = false) String componentCode,
        @ToolParam(description = "组件主键，与 componentCode 二选一", required = false) Long componentId,
        @ToolParam(description = "裁剪返回段：meta/schema/script，逗号分隔，默认全给", required = false) String fields) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("componentCode", componentCode);
        args.put("componentId", componentId);
        args.put("fields", fields);
        return toolExecution.execute(TOOL_COMPONENT_GET, args,
            ctx -> doComponentGet(ctx, componentCode, componentId, fields));
    }

    /**
     * 把 AI 拼好的链路结构存成草稿（status=DRAFT）。
     * <p>
     * EL 一律由 {@link org.dromara.databus.el.parser.generator.ExpressGenerator} 生成并校验，工具层不自己拼；内联脚本节点一律拒绝
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
    @AgentToolStep(title = "保存链路草稿", target = {"chainName", "chainCode"})
    @Tool(name = TOOL_CHAIN_DRAFT_SAVE, description = "把拼好的链路结构存成草稿（status=DRAFT），新建与修改都走这里。"
        + "同一会话可以多次新建或修改：新建不传 draftId，修改传 draftId。"
        + "cmpProperty 是画布结构树 JSON，是保存的唯一依据；EL 由服务端生成并校验，不要自己拼 EL。"
        + "链路里含内联脚本节点会被直接拒绝，必须先调 databus_component_draft_save 落脚本草稿、再把节点换成组件引用。"
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
        return toolExecution.execute(TOOL_CHAIN_DRAFT_SAVE, args, permission,
            ctx -> chainDraftSupport.doChainSave(ctx, draftId, chainCode, chainName, cmpProperty, inputParams, logLevel, note,
                expectedUpdateTime, force));
    }

    /**
     * 对当前用户的草稿链路发起一次试跑，只回摘要与节点级错误。
     * <p>
     * 与预览页「试运行」共用 {@link org.dromara.databus.service.IDatabusPreviewRunService#run}：生成 EL → 校验 → 真执行 → 汇总。
     * 试运行不挂牌，不写 {@code databus_execution}、不落快照、不占纠错轮数；整包日志与上下文快照
     * 一律不回传，模型只拿 success / costMs / nodeErrors 做自纠。
     *
     * @param chainId     要试跑的草稿链路 id
     * @param payloadJson 试跑入参 JSON，作为上下文根 $
     * @return JSON 结果
     */
    @AgentToolStep(title = "试跑链路")
    @Tool(name = TOOL_CHAIN_RUN, description = "对草稿链路发起一次试跑：按已存结构生成 EL 并真实执行，"
        + "返回 success、costMs、节点级错误 nodeErrors（含 nodeName 与错误原因）、输出片段 output 与断言结果 checks。"
        + "payloadJson 是试跑入参 JSON，作为上下文根 $；不传按空入参跑。"
        + "outputPaths 是 JSONPath 数组字符串（如 [\"$.user.id\"]），只回这些路径的值；不传则回上下文各顶层字段摘要。"
        + "expect 是断言数组字符串（如 [{\"path\":\"$.count\",\"op\":\"gte\",\"value\":1}]），只做判定不落库。"
        + "执行前受会话审批模式约束：需要审批时返回 errorType=REQUIRE_APPROVAL，确认卡片会由前端展示，"
        + "你只需简短说一句「需要你确认」，不要复述影响清单、不要用文字再问一遍；"
        + "userConfirmed 必须对应本轮收到的【卡片回执】，自行判断传 true 会被拒绝。"
        + "只能试跑草稿态、自己创建的链路；试跑不发布、不写执行记录，也不占纠错轮数。"
        + "失败时按 nodeErrors 改结构后再调 databus_chain_draft_save，不要原样重跑；"
        + "不返回整包日志和上下文快照。身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String chainRun(
        @ToolParam(description = "要试跑的草稿链路 id") Long chainId,
        @ToolParam(description = "试跑入参 JSON，作为上下文根 $；不传按空入参跑", required = false) String payloadJson,
        @ToolParam(description = "是否已得到用户明确同意（用户回复确认后再传 true）", required = false) Boolean userConfirmed,
        @ToolParam(description = "要回传的输出路径，JSONPath 数组字符串；不传回上下文摘要", required = false) String outputPaths,
        @ToolParam(description = "期望断言，JSON 数组字符串 [{path,op,value}]，只判定不落库", required = false) String expect) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chainId", chainId);
        args.put("payloadJson", payloadJson);
        args.put("userConfirmed", userConfirmed);
        args.put("outputPaths", outputPaths);
        args.put("expect", expect);
        return toolExecution.execute(TOOL_CHAIN_RUN, args, PERMISSION_RUN,
            ctx -> chainRunSupport.doChainRun(ctx, chainId, payloadJson, userConfirmed, outputPaths, expect));
    }

    /**
     * 向用户提一个需要拍板的问题：单选 / 多选 / 自由输入三合一。
     * <p>
     * 卡片由服务端生成并推送，本轮到此为止；用户点选后回执随下一条消息回来，
     * 服务端会按卡片选项做白名单校验，模型不能伪造 action。
     *
     * @param question      问题正文
     * @param options       选项清单
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入
     * @return JSON 结果
     */
    @AgentToolStep(title = "向你提问")
    @Tool(name = TOOL_ASK_USER, description = "向用户提一个需要拍板的问题：单选、多选、自由输入三合一。"
        + "适合「请选一个方案 / 勾选要处理的项 / 还需要用户补充说明」这类必须由人决定的分叉。"
        + "options 给 1-6 个，每项必须带 key（英文短标识）、label（按钮文案）、description（说明点了会发生什么）；"
        + "允许多选把 multiSelect 设为 true，允许用户自己写答案把 allowFreeText 设为 true。"
        + "question 只写要用户决定什么，不要写「可多选」「单选」「请选择」这类选择方式提示，多选会渲染复选框、单选不会，用户自己看得出来。"
        + "调用后本轮必须结束，等待卡片回执；不要再用文字重复同一个问题，也不要在同一轮继续往下做。"
        + "审批放行有专门的确认卡，不要用本工具替代。身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String askUser(
        @ToolParam(description = "问题正文，一句话说清要用户决定什么") String question,
        @ToolParam(description = "选项列表，1-6 个，每项含 key / label / description") List<AskOption> options,
        @ToolParam(description = "是否允许多选，默认 false", required = false) Boolean multiSelect,
        @ToolParam(description = "是否允许用户自由输入（相当于 E 选项），默认 false", required = false) Boolean allowFreeText) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("question", question);
        args.put("options", options);
        args.put("multiSelect", multiSelect);
        args.put("allowFreeText", allowFreeText);
        return toolExecution.execute(TOOL_ASK_USER, args,
            ctx -> doAskUser(ctx, question, options, multiSelect, allowFreeText));
    }

    /**
     * 落脚本草稿（AI 造组件第一步）：编译通过才建 DRAFT 行，不注册、不投放。
     * <p>
     * 「能编译」只是入场券：草稿不进物料面板、不注册 FlowBus，必须由人在审阅页点采纳才生效。
     * 工具只返回草稿 id 与物化契约，模型不得声称「已生效」。
     *
     * @param componentCode 组件编码
     * @param componentName 组件名称
     * @param scriptBody    完整 Java 源码
     * @param category      台账五分类
     * @param groupName     物料面板分组
     * @param description   组件描述
     * @param inputParams   试运行入参登记表 JSON 数组
     * @param componentId   已有草稿 id（更新时传）
     * @return JSON 结果
     */
    @AgentToolStep(title = "落组件草稿", target = {"componentName", "componentCode"})
    @Tool(name = TOOL_COMPONENT_DRAFT_SAVE, description = "把一段 Java 脚本源码落成组件草稿（DRAFT）。"
        + "服务端先编译，编译通过才建草稿行；草稿不注册 FlowBus、不进物料面板，只有发起人能看到和修改，"
        + "必须由人在组件审阅页点「采纳」才生效——落完草稿不要声称组件已可用，要提示用户去审阅页确认。"
        + "scriptBody 必须是完整 Java 类源码：含 @DatabusCmp 主类（继承 NodeComponent）和内嵌 Cfg 配置类；"
        + "动手前先 databus_component_get 看同类脚本的写法，不要凭记忆拼。"
        + "试运行用的那份入参务必通过 inputParams 登记进来——人点草稿卡上的播放钮时，预览链路按登记表预填入参，"
        + "不登记用户就只能对着空对象 {} 自己猜。"
        + "返回 draftId、componentCode、nodeType、参数契约 paramSchema 与危险 API 提示 risks。"
        + "改自己名下的草稿传 componentId，新建不传。身份由服务端会话绑定，不要传 userId 之类的身份参数。")
    public String scriptDraftSave(
        @ToolParam(description = "组件编码，全局唯一；新建必填") String componentCode,
        @ToolParam(description = "组件名称，新建必填") String componentName,
        @ToolParam(description = "完整 Java 类源码（@DatabusCmp 主类 + 内嵌 Cfg）") String scriptBody,
        @ToolParam(description = "台账五分类：PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM，缺省 PLATFORM", required = false) String category,
        @ToolParam(description = "物料面板分组 key（flow/sequence/branch/loop/other/subflow/business），可空", required = false) String groupName,
        @ToolParam(description = "组件描述", required = false) String description,
        @ToolParam(description = "试运行入参登记表 JSON 数组，强烈建议填：把组件试运行用的入参按 "
            + "[{\"path\":\"$.name\",\"type\":\"text\",\"defaultValue\":\"张三\",\"required\":true}] 登记，"
            + "path 是请求根 $ 下的路径；不传或传 [] 表示不登记。", required = false) String inputParams,
        @ToolParam(description = "已有草稿的组件 id；传了就更新该草稿，不传则新建", required = false) Long componentId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("componentCode", componentCode);
        args.put("componentName", componentName);
        args.put("scriptBody", scriptBody);
        args.put("category", category);
        args.put("groupName", groupName);
        args.put("description", description);
        args.put("inputParams", inputParams);
        args.put("componentId", componentId);
        return toolExecution.execute(TOOL_COMPONENT_DRAFT_SAVE, args, PERMISSION_WRITE,
            ctx -> componentDraftSupport.doScriptDraftSave(ctx, componentId, componentCode, componentName, scriptBody,
                category, groupName, description, inputParams));
    }

    /**
     * 生成选择卡并推给前端。
     *
     * @param context       调用上下文
     * @param question      问题正文
     * @param options       已校验的选项
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入
     * @return 选择卡
     */
    private static AgentCard buildAskUserCard(AgentRunContext context, String question,
                                              List<AgentCard.Option> options, boolean multiSelect,
                                              boolean allowFreeText) {
        String runId = context.runId() == null ? "run" : context.runId();
        String cardId = runId + ":card-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return AgentCard.choice(cardId, question, options, multiSelect, allowFreeText);
    }

    /**
     * 提问工具的业务体：校验选项 → 推卡 → 告诉模型本轮到此为止。
     *
     * @param context       调用上下文
     * @param question      问题正文
     * @param options       选项清单
     * @param multiSelect   是否多选
     * @param allowFreeText 是否允许自由输入
     * @return 结果载荷
     */
    private Map<String, Object> doAskUser(AgentRunContext context, String question, List<AskOption> options,
                                          Boolean multiSelect, Boolean allowFreeText) {
        String title = trimToNull(question);
        if (title == null) {
            throw new IllegalArgumentException("question 不能为空");
        }
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("options 至少给 1 个选项");
        }
        if (options.size() > MAX_CARD_OPTIONS) {
            throw new IllegalArgumentException("options 最多 " + MAX_CARD_OPTIONS + " 个");
        }
        List<AgentCard.Option> cardOptions = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (int i = 0; i < options.size(); i++) {
            AskOption option = options.get(i);
            String label = option == null ? null : trimToNull(option.label());
            if (label == null) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个选项缺少 label");
            }
            String key = option.key() == null ? "" : option.key().trim();
            // 没给 key 就按 A/B/C 顺序补，保证回执一定有稳定标识
            if (key.isEmpty()) {
                key = String.valueOf((char) ('A' + i));
            }
            if (key.length() > 32) {
                throw new IllegalArgumentException("选项 key 长度不能超过 32：" + key);
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException("选项 key 重复：" + key);
            }
            String description = trimToNull(option.description());
            cardOptions.add(new AgentCard.Option(key, label,
                description == null ? label : description, "default"));
        }

        AgentCard card = buildAskUserCard(context, title, cardOptions,
            Boolean.TRUE.equals(multiSelect), Boolean.TRUE.equals(allowFreeText));
        boolean delivered = cardChannel.send(context.runId(), card);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cardId", card.cardId());
        payload.put("cardDelivered", delivered);
        payload.put("awaitingUser", true);
        payload.put("message", delivered
            ? "选择卡片已展示，本轮到此为止：不要再用文字重复提问，收到【卡片回执】后再继续。"
            : "选择卡片未能送达前端（连接可能已断开），本轮到此为止：请提示用户刷新后重试。");
        return payload;
    }

    private Map<String, Object> doComponentGet(AgentRunContext context, String componentCode,
                                               Long componentId, String fields) {
        String code = trimToNull(componentCode);
        if (componentId == null && code == null) {
            throw new IllegalArgumentException("componentCode 与 componentId 至少给一个");
        }

        DatabusComponentVo cmp;
        if (componentId != null) {
            cmp = componentService.queryById(componentId);
        } else {
            // 先找生效/停用的正式组件，找不到再回捞自己的草稿，避免草稿与正式同码时把草稿当正式用
            cmp = findComponentByCode(code, null);
            if (cmp == null) {
                cmp = findComponentByCode(code, STATUS_DRAFT);
            }
        }
        if (cmp == null) {
            throw new IllegalArgumentException("组件不存在：" + (code == null ? componentId : code)
                + "（他人的草稿不可见；自己的草稿需用 componentCode 查）");
        }
        boolean draft = STATUS_DRAFT.equals(cmp.getStatus());
        if (draft && !Objects.equals(cmp.getCreateBy(), context.userId())) {
            throw new IllegalArgumentException("组件 " + cmp.getComponentCode() + " 是他人的草稿，不可读取");
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
            payload.put("draft", draft);
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

    private DatabusComponentVo findComponentByCode(String code, String status) {
        DatabusComponentBo bo = new DatabusComponentBo();
        bo.setComponentCode(code);
        bo.setStatus(status);
        PageResult<DatabusComponentVo> page = componentService.queryPageList(bo, pageQuery(0, MAX_PAGE_SIZE));
        return page.getRows().stream()
            .filter(row -> row.getComponentCode() != null && row.getComponentCode().equalsIgnoreCase(code))
            .findFirst()
            .orElse(null);
    }

    private static PageQuery pageQuery(int offset, int size) {
        return new PageQuery(size, offset / size + 1);
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

    /**
     * {@code databus_ask_user} 的选项入参。
     *
     * @param key         回传的动作键，缺省按 A/B/C 生成
     * @param label       按钮文案
     * @param description 选项自述，缺省用 label 兜底
     */
    public record AskOption(String key, String label, String description) {
    }

}
