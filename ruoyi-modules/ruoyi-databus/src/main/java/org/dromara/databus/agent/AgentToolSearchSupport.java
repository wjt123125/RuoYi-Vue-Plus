package org.dromara.databus.agent;

import lombok.RequiredArgsConstructor;
import static org.dromara.databus.agent.AgentToolText.trimToNull;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.sql.QueryOptions;
import org.dromara.databus.connector.sql.SqlConnector;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.ComponentOptionVo;
import org.dromara.databus.domain.vo.ConnectionOptionVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.IDatabusComponentService;
import org.dromara.databus.service.ISysDatabusConnectionService;
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

import static org.dromara.databus.constant.AgentConstants.DEFAULT_PAGE_SIZE;
import static org.dromara.databus.constant.AgentConstants.MAX_PAGE_SIZE;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;

/**
 * 只读检索与详情工具的业务实现：链路 / 物料 / 连接 / 连接器类型 / 库表结构检索，以及链路详情读取。
 * <p>
 * 由 DatabusAgentTools 委托调用，不直接注册为工具。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentToolSearchSupport {

    /**
     * 表结构查询单次最多取回的行数（表清单 / 字段清单共用）
     */
    private static final int MAX_SCHEMA_ROWS = 2000;

    /**
     * 单张表最多回传的字段数，避免宽表撑爆上下文
     */
    private static final int MAX_SCHEMA_COLUMNS_PER_TABLE = 100;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final IDatabusChainService chainService;
    private final IDatabusComponentService componentService;
    private final ISysDatabusConnectionService connectionService;
    private final ConnectorRegistry connectorRegistry;

    public Map<String, Object> doChainSearch(AgentRunContext context, String keyword, Integer pageSize, String cursor) {
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

    public Map<String, Object> doChainGet(AgentRunContext context, Long chainId) {
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

    public Map<String, Object> doComponentSearch(String keyword, String tags, String nodeType,
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
            item.put("needsConnector", cmp.needsConnector());
            item.put("connectorFamily", cmp.connectorFamily());
            items.add(item);
        }
        return pagePayload(matched.size(), items, offset, size);
    }

    public Map<String, Object> doConnectionSearch(String keyword, String connectorFamily,
                                                  Integer pageSize, String cursor) {
        String kw = trimToNull(keyword);
        String family = trimToNull(connectorFamily);
        int size = normalizePageSize(pageSize);
        int offset = parseCursor(cursor);

        List<ConnectionOptionVo> matched = new ArrayList<>();
        for (ConnectionOptionVo conn : connectionService.queryOptions()) {
            // 族为空代表连接器未装载，此时不做族过滤（与前端「宁可多列不可用项」同口径）
            if (family != null && !family.equalsIgnoreCase(conn.connectorFamily())) {
                continue;
            }
            if (kw != null && !matchesConnection(conn, kw)) {
                continue;
            }
            matched.add(conn);
        }
        if (kw != null) {
            matched.sort(Comparator.comparingInt((ConnectionOptionVo conn) -> connectionRelevance(conn, kw))
                .thenComparing(conn -> conn.connectionId() == null ? "" : conn.connectionId()));
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = offset; i < matched.size() && items.size() < size; i++) {
            ConnectionOptionVo conn = matched.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("connectionId", conn.connectionId());
            item.put("connectionName", conn.connectionName());
            item.put("connectorType", conn.connectorType());
            item.put("connectorFamily", conn.connectorFamily());
            items.add(item);
        }
        Map<String, Object> payload = pagePayload(matched.size(), items, offset, size);
        if (matched.isEmpty()) {
            payload.put("message", "没有匹配的已启用连接，不要编造 connectionId，"
                + "请让用户确认连接已创建并启用（连接器未装载时 connectorFamily 为空）。");
        }
        return payload;
    }

    private static boolean matchesConnection(ConnectionOptionVo conn, String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT);
        return lower(conn.connectionId()).contains(k) || lower(conn.connectionName()).contains(k);
    }

    private static int connectionRelevance(ConnectionOptionVo conn, String keyword) {
        String k = keyword.toLowerCase(Locale.ROOT);
        String id = lower(conn.connectionId());
        if (id.equals(k)) {
            return 0;
        }
        if (id.startsWith(k)) {
            return 1;
        }
        return lower(conn.connectionName()).contains(k) ? 2 : 3;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> doConnectorSearch(String keyword) {
        String kw = trimToNull(keyword);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map.Entry<String, Connector> entry : connectorRegistry.all().entrySet()) {
            ConnectorDescriptor descriptor;
            try {
                descriptor = entry.getValue().describe();
            } catch (RuntimeException e) {
                // 单个连接器 describe 失败不影响整张能力清单
                log.warn("[databus-agent] 连接器 {} describe 失败，已跳过: {}", entry.getKey(), e.getMessage());
                continue;
            }
            if (descriptor == null) {
                continue;
            }
            String type = descriptor.getType() == null ? entry.getKey() : descriptor.getType();
            String label = descriptor.getLabel();
            String family = descriptor.getFamily();
            if (kw != null && !containsIgnoreCase(type, kw)
                && !containsIgnoreCase(label, kw) && !containsIgnoreCase(family, kw)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("version", versionOfKey(entry.getKey()));
            item.put("label", label);
            item.put("family", family);
            item.put("sqlCapable", entry.getValue() instanceof SqlConnector);
            List<Map<String, Object>> operations = new ArrayList<>();
            for (ConnectorDescriptor.OperationDef op : descriptor.getOperations()) {
                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put("name", op.getName());
                operation.put("label", op.getLabel());
                operation.put("componentId", op.getComponentId());
                operations.add(operation);
            }
            item.put("operations", operations);
            item.put("configFields", configFieldSummary(descriptor));
            items.add(item);
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("type"))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalCount", items.size());
        payload.put("items", items);
        if (items.isEmpty()) {
            payload.put("message", "没有匹配的连接器：平台未安装该类型连接器，请让用户到物料市场安装并启用后再建连接。");
        }
        return payload;
    }

    /**
     * 配置字段摘要：只给「要填什么」，不给任何真实配置值。
     */
    private static List<Map<String, Object>> configFieldSummary(ConnectorDescriptor descriptor) {
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Map.Entry<String, ConnectorDescriptor.ConfigField> entry : descriptor.getConfigSchema().entrySet()) {
            ConnectorDescriptor.ConfigField field = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", entry.getKey());
            item.put("label", field.getLabel());
            item.put("type", field.getType());
            item.put("required", field.isRequired());
            item.put("sensitive", field.isSensitive());
            item.put("description", field.getDescription());
            fields.add(item);
        }
        return fields;
    }

    private static String versionOfKey(String key) {
        int index = key == null ? -1 : key.indexOf('@');
        return index < 0 ? null : key.substring(index + 1);
    }

    /**
     * 连接查不到时的报错文案：区分「真没有这个连接」与「连接在，但后端装载失败」。
     * <p>
     * 后者在 {@code loadEnabledConnections()} 里被静默跳过（单条转换失败不阻断其它连接），
     * 如果笼统报「不存在或已停用」，会让用户白跑一趟去重建连接。
     */
    private String connectionMissingMessage(String connId) {
        boolean loadFailed = connectionService.queryOptions().stream()
            .anyMatch(option -> connId.equalsIgnoreCase(option.connectionId()));
        return loadFailed
            ? "连接 " + connId + " 存在但后端加载失败（连接器版本或凭据异常），请让用户查看后端日志里的「连接转换失败」"
            : "连接 " + connId + " 不存在或已停用：先调 databus_connection_search 确认，不要拿老链路的连接顶替";
    }

    /**
     * 表结构查询：只读 information_schema / pg_catalog，不碰业务数据行。
     * <p>
     * 连接必须来自 {@code connection_search} 且处于启用态；连接器必须是 SqlConnector，
     * 方言当前只支持 MySQL / PostgreSQL，其余连接器明确报错让用户提供结构，不猜。
     */
    public Map<String, Object> doDbSchemaSearch(String connectionId, String keyword,
                                                Integer pageSize, String cursor) {
        String connId = trimToNull(connectionId);
        if (connId == null) {
            throw new IllegalArgumentException("connectionId 不能为空：先调 databus_connection_search 拿连接，再查表结构");
        }
        Connection conn = connectionService.loadEnabledConnections().stream()
            .filter(item -> connId.equalsIgnoreCase(item.getId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(connectionMissingMessage(connId)));

        Connector connector = connectorRegistry.ofType(conn.getConnectorType(), conn.getConnectorVersion());
        if (!(connector instanceof SqlConnector sqlConnector)) {
            throw new IllegalArgumentException("连接 " + connId + " 的连接器 " + conn.getConnectorType()
                + " 不是 SQL 连接器，无法查表结构；请让用户直接提供表与字段");
        }
        String connectorType = lower(conn.getConnectorType());
        boolean mysql;
        if (connectorType.contains("mysql")) {
            mysql = true;
        } else if (connectorType.contains("postgres") || connectorType.contains("pgsql")) {
            mysql = false;
        } else {
            throw new IllegalArgumentException("连接 " + connId + " 的连接器类型 " + conn.getConnectorType()
                + " 暂不支持表结构查询（当前支持 MySQL / PostgreSQL）：请让用户直接提供表名与字段");
        }

        String kw = trimToNull(keyword);
        int size = normalizePageSize(pageSize);
        int offset = parseCursor(cursor);
        // 列名统一转小写，避免不同驱动大小写差异让模型对不上字段
        QueryOptions opts = new QueryOptions(null, MAX_SCHEMA_ROWS, 200, "LOWER");

        List<Map<String, Object>> tableRows = sqlConnector.queryForList(conn,
            mysql
                ? "SELECT table_name AS table_name, table_comment AS table_comment FROM information_schema.tables "
                    + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE' ORDER BY table_name"
                : "SELECT c.relname AS table_name, obj_description(c.oid, 'pg_class') AS table_comment "
                    + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                    + "ORDER BY c.relname",
            List.of(), opts);

        Map<String, String> tableComments = new LinkedHashMap<>();
        for (Map<String, Object> row : tableRows) {
            String table = stringValue(row.get("table_name"));
            if (table != null) {
                tableComments.put(table, stringValue(row.get("table_comment")));
            }
        }

        List<String> matched = new ArrayList<>();
        if (kw == null) {
            matched.addAll(tableComments.keySet());
        } else {
            for (Map.Entry<String, String> entry : tableComments.entrySet()) {
                if (containsIgnoreCase(entry.getKey(), kw) || containsIgnoreCase(entry.getValue(), kw)) {
                    matched.add(entry.getKey());
                }
            }
            if (matched.isEmpty()) {
                // 表名/表注释没命中时按字段名反查所属表
                List<Map<String, Object>> columnHits = sqlConnector.queryForList(conn,
                    mysql
                        ? "SELECT DISTINCT table_name AS table_name FROM information_schema.columns "
                            + "WHERE table_schema = DATABASE() AND column_name LIKE ? ORDER BY table_name"
                        : "SELECT DISTINCT c.relname AS table_name FROM pg_class c "
                            + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                            + "JOIN pg_attribute a ON a.attrelid = c.oid "
                            + "WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                            + "AND a.attnum > 0 AND NOT a.attisdropped AND a.attname LIKE ? ORDER BY c.relname",
                    List.<Object>of("%" + kw + "%"), opts);
                for (Map<String, Object> row : columnHits) {
                    String table = stringValue(row.get("table_name"));
                    if (table != null && !matched.contains(table)) {
                        matched.add(table);
                    }
                }
            }
        }

        List<String> pageTables = new ArrayList<>();
        for (int i = offset; i < matched.size() && pageTables.size() < size; i++) {
            pageTables.add(matched.get(i));
        }
        Map<String, List<Map<String, Object>>> columnsByTable = queryColumns(sqlConnector, conn, mysql, pageTables, opts);

        List<Map<String, Object>> items = new ArrayList<>();
        for (String table : pageTables) {
            List<Map<String, Object>> columns = columnsByTable.getOrDefault(table, List.of());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tableName", table);
            item.put("tableComment", tableComments.get(table));
            item.put("columns", columns);
            item.put("columnsTruncated", columns.size() >= MAX_SCHEMA_COLUMNS_PER_TABLE);
            items.add(item);
        }
        Map<String, Object> payload = pagePayload(matched.size(), items, offset, size);
        payload.put("connectionId", connId);
        payload.put("connectorType", conn.getConnectorType());
        payload.put("dialect", mysql ? "mysql" : "postgresql");
        if (matched.isEmpty()) {
            payload.put("message", "该连接下没有匹配的表：换个关键词，或直接问用户表名；不要臆造表名与字段。");
        }
        return payload;
    }

    private static Map<String, List<Map<String, Object>>> queryColumns(SqlConnector sqlConnector, Connection conn,
                                                                      boolean mysql, List<String> tables,
                                                                      QueryOptions opts) {
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return columnsByTable;
        }
        String placeholders = String.join(", ", tables.stream().map(table -> "?").toList());
        String sql = mysql
            ? "SELECT table_name AS table_name, column_name AS column_name, data_type AS data_type, "
                + "column_comment AS column_comment, column_key AS column_key, is_nullable AS is_nullable "
                + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name IN (" + placeholders + ") ORDER BY table_name, ordinal_position"
            : "SELECT c.relname AS table_name, a.attname AS column_name, "
                + "format_type(a.atttypid, a.atttypmod) AS data_type, "
                + "col_description(c.oid, a.attnum) AS column_comment, "
                + "CASE WHEN EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid AND i.indisprimary "
                + "AND a.attnum = ANY(i.indkey)) THEN 'PRI' ELSE '' END AS column_key, "
                + "CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS is_nullable "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_attribute a ON a.attrelid = c.oid "
                + "WHERE c.relkind = 'r' AND n.nspname NOT IN ('pg_catalog', 'information_schema') "
                + "AND a.attnum > 0 AND NOT a.attisdropped AND c.relname IN (" + placeholders + ") "
                + "ORDER BY c.relname, a.attnum";
        List<Object> args = new ArrayList<>(tables.size());
        args.addAll(tables);
        for (Map<String, Object> row : sqlConnector.queryForList(conn, sql, args, opts)) {
            String table = stringValue(row.get("table_name"));
            if (table == null) {
                continue;
            }
            List<Map<String, Object>> columns = columnsByTable.computeIfAbsent(table, key -> new ArrayList<>());
            if (columns.size() >= MAX_SCHEMA_COLUMNS_PER_TABLE) {
                continue;
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", stringValue(row.get("column_name")));
            column.put("type", stringValue(row.get("data_type")));
            column.put("nullable", "YES".equalsIgnoreCase(stringValue(row.get("is_nullable"))));
            column.put("primaryKey", "PRI".equalsIgnoreCase(stringValue(row.get("column_key"))));
            column.put("comment", stringValue(row.get("column_comment")));
            columns.add(column);
        }
        return columnsByTable;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMATTER.format(time);
    }
}
