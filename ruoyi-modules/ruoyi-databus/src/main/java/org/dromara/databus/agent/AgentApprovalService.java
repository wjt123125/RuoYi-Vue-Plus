package org.dromara.databus.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.config.properties.DatabusAgentProperties;
import org.dromara.databus.domain.DatabusAgentApprovalSetting;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.Properties;
import org.dromara.databus.enums.AgentApprovalModeEnum;
import org.dromara.databus.mapper.DatabusAgentApprovalSettingMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 会话级操作审批：模式读写 + 链路风险扫描 + 放行判定（阶段六）。
 * <p>
 * 判定权只在本类：模型只负责把 {@link Impact} 解释给人听，不能改变放行结果。
 * AUTO 规则只依赖链路上已有的事实（组件编码、分类/分组、节点参数），
 * 不做「危险组件」人工台账；认不出来的编码一律按写类处理。
 * <p>
 * 所有规则都按「宁可多问一次」取舍：规则误判最多多问，漏判就是真实写库。
 *
 * @author databus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentApprovalService {

    /**
     * 内置 LiteFlow 流程控制节点：本身无副作用（条件/循环/异常抛捕）。
     */
    private static final Set<String> FLOW_CODES = Set.of(
        "switchRoute", "iteratorLoop", "forLoop", "exceptionThrower", "exceptionInspect");
    /**
     * 内置上下文变换节点：只读写 $ 上下文，不触达数据库/外部系统。
     * 不属于「认不出来的自定义组件」，可证明只读。
     */
    private static final Set<String> CONTEXT_ONLY_CODES = Set.of(
        "setValue", "fieldMap", "dataPatch", "response");
    /**
     * SQL 类节点编码（SQL 文本从节点参数 sql/sqls 取）。
     */
    private static final Set<String> SQL_CODES = Set.of(
        "dbQuery", "dbExecute", "dbBatch", "rdsExecute", "rdsQuery");
    /**
     * 明确的外部副作用节点（邮件/文件上传下载/消息队列）。
     */
    private static final Set<String> EXTERNAL_CODES = Set.of(
        "httpRequest", "sendMail", "fileUpload", "fileDownload");
    /**
     * 外部类编码前缀：新装连接器组件按前缀兜底识别，识别为外部＝要确认。
     */
    private static final List<String> EXTERNAL_PREFIXES = List.of(
        "http", "kafka", "mq", "mail", "file", "upload", "oss", "ftp", "sftp");
    /**
     * SQL 只读首关键字：其余（含 WITH 等无法一眼断定的）一律写类。
     */
    private static final Set<String> SQL_READ_KEYWORDS = Set.of(
        "SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN");

    private static final String KIND_READ = "READ";
    private static final String KIND_WRITE = "WRITE";
    private static final String KIND_EXTERNAL = "EXTERNAL";
    private static final String KIND_NEUTRAL = "NEUTRAL";

    private final DatabusAgentApprovalSettingMapper settingMapper;
    private final DatabusComponentMapper componentMapper;
    private final DatabusAgentProperties properties;

    /**
     * 部署是否开放「完全访问」档。
     *
     * @return 是否开放
     */
    public boolean isAllowFullAccess() {
        return properties.isAllowFullAccess();
    }

    /**
     * 读取会话当前生效的模式：无行 → MANUAL；FULL 但部署未开放 → MANUAL；CUSTOM → MANUAL。
     *
     * @param conversationId 会话 id
     * @param userId         当前用户
     * @return 生效模式，永不为 null
     */
    public AgentApprovalModeEnum getMode(String conversationId, Long userId) {
        DatabusAgentApprovalSetting setting = selectSetting(conversationId);
        if (setting == null || !ownsSetting(setting, userId)) {
            return AgentApprovalModeEnum.MANUAL;
        }
        return effective(AgentApprovalModeEnum.of(setting.getMode()));
    }

    /**
     * 保存会话模式；只允许 MANUAL/AUTO，FULL 仅在部署开放时可选，CUSTOM 暂未开放。
     *
     * @param conversationId 会话 id
     * @param userId         当前用户
     * @param modeCode       模式编码
     */
    public void saveMode(String conversationId, Long userId, String modeCode) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new ServiceException("会话 id 不能为空");
        }
        AgentApprovalModeEnum mode = AgentApprovalModeEnum.of(modeCode);
        if (AgentApprovalModeEnum.CUSTOM == mode) {
            throw new ServiceException("自定义模式暂未开放");
        }
        if (AgentApprovalModeEnum.FULL == mode && !isAllowFullAccess()) {
            throw new ServiceException("当前部署未开放完全访问模式");
        }
        DatabusAgentApprovalSetting setting = selectSetting(conversationId);
        if (setting == null) {
            DatabusAgentApprovalSetting entity = new DatabusAgentApprovalSetting();
            entity.setConversationId(conversationId);
            entity.setUserId(userId);
            entity.setMode(mode.getCode());
            settingMapper.insert(entity);
            return;
        }
        if (!ownsSetting(setting, userId)) {
            throw new ServiceException("无权修改该会话的审批模式");
        }
        setting.setUserId(userId);
        setting.setMode(mode.getCode());
        settingMapper.updateById(setting);
    }

    /**
     * 扫描链路画布 JSON，产出影响清单。
     *
     * @param root 画布结构树
     * @return 影响清单
     */
    public Impact scan(CmpProperty root) {
        if (root == null) {
            return new Impact(0, 0, false, List.of());
        }
        List<NodeRef> refs = new ArrayList<>();
        collect(root, refs);
        Map<String, DatabusComponent> components = loadComponents(refs);

        List<ImpactNode> nodes = new ArrayList<>();
        int writeCount = 0;
        int externalCount = 0;
        for (NodeRef ref : refs) {
            ImpactNode node = classify(ref, components.get(ref.code));
            nodes.add(node);
            if (KIND_WRITE.equals(node.kind())) {
                writeCount++;
            } else if (KIND_EXTERNAL.equals(node.kind())) {
                externalCount++;
            }
        }
        return new Impact(writeCount, externalCount, writeCount + externalCount > 0, nodes);
    }

    /**
     * 放行判定：FULL 直接放行；AUTO 且无写/外部节点直接放行；否则必须用户确认。
     *
     * @param mode          生效模式
     * @param impact        影响清单
     * @param userConfirmed 用户是否已明确同意
     * @return 判定结果
     */
    public Decision decide(AgentApprovalModeEnum mode, Impact impact, boolean userConfirmed) {
        if (AgentApprovalModeEnum.FULL == mode) {
            return new Decision(true, true, "完全访问：用户已授权不经审批直接运行");
        }
        if (AgentApprovalModeEnum.AUTO == mode && !impact.needConfirm()) {
            return new Decision(true, true, "自动放行：" + impact.nodes().size()
                + " 个节点均可证明只读（流程控制/上下文变换/只读 SQL/只读外部调用）");
        }
        if (userConfirmed) {
            return new Decision(true, false, "用户已确认本次试跑");
        }
        return new Decision(false, false, mode == AgentApprovalModeEnum.AUTO
            ? "自动审批：链路含写或外部调用节点，需要用户确认"
            : "手动审批：每次试跑前需要用户确认");
    }

    /**
     * 生效模式归一：FULL 部署未开放、CUSTOM 未实现，一律按 MANUAL。
     *
     * @param mode 存储模式
     * @return 生效模式
     */
    public AgentApprovalModeEnum effective(AgentApprovalModeEnum mode) {
        if (mode == null || AgentApprovalModeEnum.CUSTOM == mode) {
            return AgentApprovalModeEnum.MANUAL;
        }
        if (AgentApprovalModeEnum.FULL == mode && !isAllowFullAccess()) {
            return AgentApprovalModeEnum.MANUAL;
        }
        return mode;
    }

    private DatabusAgentApprovalSetting selectSetting(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return settingMapper.selectOne(new LambdaQueryWrapper<DatabusAgentApprovalSetting>()
            .eq(DatabusAgentApprovalSetting::getConversationId, conversationId)
            .last("limit 1"));
    }

    private static boolean ownsSetting(DatabusAgentApprovalSetting setting, Long userId) {
        return setting.getUserId() == null || Objects.equals(setting.getUserId(), userId);
    }

    private static void collect(CmpProperty node, List<NodeRef> refs) {
        if (node == null) {
            return;
        }
        Properties props = node.getProperties();
        String code = props != null ? trimToNull(props.getId()) : null;
        if (code == null) {
            code = trimToNull(node.getId());
        }
        if (code != null) {
            String title = props != null ? trimToNull(props.getTitle()) : null;
            String tag = props != null ? trimToNull(props.getTag()) : null;
            String data = props != null ? props.getData() : null;
            refs.add(new NodeRef(code, title, tag, data));
        }
        collect(node.getCondition(), refs);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collect(child, refs);
            }
        }
    }

    private Map<String, DatabusComponent> loadComponents(List<NodeRef> refs) {
        Set<String> codes = new LinkedHashSet<>();
        for (NodeRef ref : refs) {
            codes.add(ref.code);
        }
        if (codes.isEmpty()) {
            return Map.of();
        }
        List<DatabusComponent> rows = componentMapper.selectList(new LambdaQueryWrapper<DatabusComponent>()
            .in(DatabusComponent::getComponentCode, codes));
        Map<String, DatabusComponent> map = new HashMap<>();
        for (DatabusComponent row : rows) {
            map.put(row.getComponentCode(), row);
        }
        return map;
    }

    private ImpactNode classify(NodeRef ref, DatabusComponent component) {
        String code = ref.code;
        String category = component == null ? null : component.getCategory();
        String group = component == null ? null : component.getGroupName();
        String name = nodeName(ref, component);

        // 1. 流程控制节点：本身无副作用
        if (FLOW_CODES.contains(code) || "FLOW_CONTROL".equalsIgnoreCase(category)
            || "flow".equalsIgnoreCase(group)) {
            return new ImpactNode(ref.nodeId(), code, name, KIND_NEUTRAL, "流程控制节点，无外部副作用");
        }
        // 2. SQL 类节点：按首个关键字判
        if (SQL_CODES.contains(code) || isSqlNode(code, ref.data)) {
            return classifySql(ref, name);
        }
        // 3. 外部调用节点：GET/HEAD 只读，其余或判不出＝外部
        if (EXTERNAL_CODES.contains(code) || "PROTOCOL".equalsIgnoreCase(category) || hasExternalPrefix(code)) {
            return classifyExternal(ref, name);
        }
        // 4. 内置上下文变换节点：只读写 $，可证明无外部副作用
        if (CONTEXT_ONLY_CODES.contains(code) && ("DATA".equalsIgnoreCase(category) || component == null)) {
            return new ImpactNode(ref.nodeId(), code, name, KIND_NEUTRAL, "内置上下文变换节点，无外部副作用");
        }
        // 5. 平台业务节点与其余（自定义脚本/新装连接器/台账查不到）一律写类
        return new ImpactNode(ref.nodeId(), code, name, KIND_WRITE, component == null
            ? "组件不在内置规则内，无法证明只读"
            : "平台业务节点，可能有写副作用");
    }

    private ImpactNode classifySql(NodeRef ref, String name) {
        List<String> statements = extractSql(ref.data);
        if (statements.isEmpty()) {
            return new ImpactNode(ref.nodeId(), ref.code, name, KIND_WRITE, "取不到节点 SQL 文本，按写类");
        }
        for (String sql : statements) {
            String trimmed = sql == null ? "" : sql.trim();
            if (trimmed.isEmpty()) {
                return new ImpactNode(ref.nodeId(), ref.code, name, KIND_WRITE, "SQL 文本为空，按写类");
            }
            String keyword = firstKeyword(trimmed);
            if (!SQL_READ_KEYWORDS.contains(keyword)) {
                return new ImpactNode(ref.nodeId(), ref.code, name, KIND_WRITE,
                    "SQL 首关键字 " + (keyword.isEmpty() ? "（空）" : keyword) + "，按写类");
            }
            if (hasMultipleStatements(trimmed)) {
                return new ImpactNode(ref.nodeId(), ref.code, name, KIND_WRITE, "SQL 含多条语句，按写类");
            }
        }
        return new ImpactNode(ref.nodeId(), ref.code, name, KIND_READ,
            "只读 SQL（" + firstKeyword(statements.get(0)) + "）");
    }

    private ImpactNode classifyExternal(NodeRef ref, String name) {
        String method = extractMethod(ref.data);
        if (method == null) {
            return new ImpactNode(ref.nodeId(), ref.code, name, KIND_EXTERNAL, "外部调用且判不出 method，需要确认");
        }
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return new ImpactNode(ref.nodeId(), ref.code, name, KIND_READ, "只读外部调用（" + method + "）");
        }
        return new ImpactNode(ref.nodeId(), ref.code, name, KIND_EXTERNAL, "外部调用（" + method + "），需要确认");
    }

    private static boolean isSqlNode(String code, String data) {
        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.startsWith("sql")) {
            return true;
        }
        Map<String, Object> cfg = parseCfg(data);
        return cfg != null && (cfg.get("sql") != null || cfg.get("sqls") != null);
    }

    private static boolean hasExternalPrefix(String code) {
        String lower = code.toLowerCase(Locale.ROOT);
        for (String prefix : EXTERNAL_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String nodeName(NodeRef ref, DatabusComponent component) {
        if (ref.title != null) {
            return ref.title;
        }
        if (component != null) {
            if (component.getComponentName() != null && !component.getComponentName().isBlank()) {
                return component.getComponentName();
            }
            if (component.getShortName() != null && !component.getShortName().isBlank()) {
                return component.getShortName();
            }
        }
        if (ref.tag != null) {
            return ref.tag;
        }
        return ref.code;
    }

    private static Map<String, Object> parseCfg(String data) {
        if (data == null || data.isBlank() || !JsonUtils.isJsonObject(data)) {
            return null;
        }
        try {
            return JsonUtils.parseMap(data);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> extractSql(String data) {
        List<String> sqls = new ArrayList<>();
        Map<String, Object> cfg = parseCfg(data);
        if (cfg == null) {
            return sqls;
        }
        Object sql = cfg.get("sql");
        if (sql instanceof String text && !text.isBlank()) {
            sqls.add(text);
        }
        Object batch = cfg.get("sqls");
        if (batch instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String text && !text.isBlank()) {
                    sqls.add(text);
                } else if (item instanceof Map<?, ?> map) {
                    Object nested = map.get("sql");
                    if (nested instanceof String text && !text.isBlank()) {
                        sqls.add(text);
                    }
                }
            }
        }
        return sqls;
    }

    private static String extractMethod(String data) {
        Map<String, Object> cfg = parseCfg(data);
        if (cfg == null) {
            return null;
        }
        Object method = cfg.get("method") != null ? cfg.get("method") : cfg.get("httpMethod");
        if (method instanceof String text && !text.isBlank()) {
            return text.trim().toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /**
     * 跳过前导空行与 -- / 块注释后取首个字母单词。
     */
    private static String firstKeyword(String sql) {
        String text = sql == null ? "" : sql.trim();
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            if (text.startsWith("--")) {
                int end = text.indexOf('\n');
                text = end < 0 ? "" : text.substring(end + 1).trim();
                stripped = true;
            } else if (text.startsWith("/*")) {
                int end = text.indexOf("*/");
                text = end < 0 ? "" : text.substring(end + 2).trim();
                stripped = true;
            }
        }
        int end = 0;
        while (end < text.length() && Character.isLetter(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end).toUpperCase(Locale.ROOT);
    }

    private static boolean hasMultipleStatements(String sql) {
        String text = sql.trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text.indexOf(';') >= 0;
    }

    private static String trimToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 扫描中间态：一个节点在画布上出现的事实（编码/标题/tag/参数）。
     */
    private record NodeRef(String code, String title, String tag, String data) {

        String nodeId() {
            return code;
        }
    }

    /**
     * 影响清单里的单个节点。
     *
     * @param nodeId 节点编码（即 EL nodeId）
     * @param code   组件编码
     * @param name   展示名（优先节点标题，其次组件名，最后编码）
     * @param kind   READ/WRITE/EXTERNAL/NEUTRAL
     * @param reason 判定依据（会写进 step 审计与模型回包）
     */
    public record ImpactNode(String nodeId, String code, String name, String kind, String reason) {
    }

    /**
     * 影响清单。
     *
     * @param writeCount    写类节点数
     * @param externalCount 外部调用节点数
     * @param needConfirm   是否必须确认
     * @param nodes         逐节点判定
     */
    public record Impact(int writeCount, int externalCount, boolean needConfirm, List<ImpactNode> nodes) {
    }

    /**
     * 放行判定结果。
     *
     * @param allowed   是否放行
     * @param autoPass  是否规则自动放行（FULL 或 AUTO 纯只读）
     * @param reason    判定依据
     */
    public record Decision(boolean allowed, boolean autoPass, String reason) {
    }

}