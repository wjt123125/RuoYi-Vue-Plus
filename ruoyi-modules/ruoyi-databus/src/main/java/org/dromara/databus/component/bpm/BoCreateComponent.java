package org.dromara.databus.component.bpm;

import com.jayway.jsonpath.TypeRef;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.BoCreateRequest;
import org.dromara.databus.connector.bpm.dto.BoItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BO_CREATE 组件（注册名 {@code boCreate}）。
 * <p>
 * 调 BPM 端 BO_CREATE 端点创建 BO 数据。响应 {@code {boResults: [{boName, records, createdCount}]}}
 * 整体存入 {@code $.<tag>.boResults}，并按 cfg.boList[i].rewrite.strategy 执行 6 种回写策略
 * （no/all/boId/add/exclude/include），策略语义与老系统 BoCreateProcessor 一致。
 *
 * <p>BPM 端不做回写（决策 9.1.2），回写在 Component 层完成；BPM 端不做字段重映射
 * （决策 9.1.3），Component 层在 boCreate 之前接 fieldMap 把数据形状准备好。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("boCreate")
public class BoCreateComponent extends DatabusNodeComponent {

    private static final String DEFAULT_METHOD = "create";

    private static final TypeRef<List<Map<String, Object>>> RECORDS_TYPE =
            new TypeRef<List<Map<String, Object>>>() {};

    private static final TypeRef<List<Map<String, Object>>> BO_RESULTS_TYPE =
            new TypeRef<List<Map<String, Object>>>() {};

    @Override
    public void process() {
        BoCreateCfg cfg = this.getCmpData(BoCreateCfg.class);
        if (cfg == null) {
            throw new ServiceException("BO_CREATE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("BO_CREATE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("BO_CREATE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getUid() == null || cfg.getUid().isBlank()) {
            throw new ServiceException("BO_CREATE 组件缺少 uid 配置（tag=" + tag + "）");
        }
        if (cfg.getBoList() == null || cfg.getBoList().isEmpty()) {
            throw new ServiceException("BO_CREATE 组件缺少 boList 配置（tag=" + tag + "）");
        }

        // 参数解析：字符串支持裸路径 / 混合字符串 / 字面量
        String connectionId = resolveStr(cfg.getConnectionId());
        String method = cfg.getMethod() == null || cfg.getMethod().isBlank()
                ? DEFAULT_METHOD : resolveStr(cfg.getMethod());
        if (!"create".equals(method) && !"createDataBO".equals(method)) {
            throw new ServiceException("BO_CREATE 组件 method 仅支持 create / createDataBO，实际: " + method);
        }
        String bindId = resolveStr(cfg.getBindId());
        if ("create".equals(method) && (bindId == null || bindId.isBlank())) {
            throw new ServiceException("BO_CREATE 组件 method=create 时 bindId 必填（tag=" + tag + "）");
        }
        String uid = resolveStr(cfg.getUid());

        // 组装 boList：从 sourcePath 读 List<Map> 作为 records
        List<BoItem> boItems = new ArrayList<>(cfg.getBoList().size());
        for (int i = 0; i < cfg.getBoList().size(); i++) {
            BoCreateCfg.BoItemCfg itemCfg = cfg.getBoList().get(i);
            if (itemCfg == null || itemCfg.getBoName() == null || itemCfg.getBoName().isBlank()) {
                throw new ServiceException("BO_CREATE 组件 boList[" + i + "] 缺少 boName（tag=" + tag + "）");
            }
            if (itemCfg.getSourcePath() == null || itemCfg.getSourcePath().isBlank()) {
                throw new ServiceException("BO_CREATE 组件 boList[" + i + "] 缺少 sourcePath（tag=" + tag + "）");
            }
            List<Map<String, Object>> records = get(itemCfg.getSourcePath(), RECORDS_TYPE);
            if (records == null || records.isEmpty()) {
                throw new ServiceException("BO_CREATE 组件 boList[" + i + "] sourcePath 读到的 records 为空: "
                        + itemCfg.getSourcePath());
            }
            BoItem item = new BoItem();
            item.setBoName(itemCfg.getBoName());
            item.setRecords(records);
            boItems.add(item);
        }

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(connectionId);

        BoCreateRequest request = new BoCreateRequest();
        request.setMethod(method);
        request.setBindId(bindId);
        request.setUid(uid);
        request.setBoList(boItems);

        Object result = connector.boCreate(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("BO_CREATE 响应非 JSON 对象: " + result);
        }
        Object boResultsRaw = resultMap.get("boResults");
        if (!(boResultsRaw instanceof List<?>)) {
            throw new ServiceException("BO_CREATE 响应 boResults 非 List: " + boResultsRaw);
        }
        List<Map<String, Object>> boResults = convertResults(boResultsRaw);

        // 整体 boResults 存一份到 $.<tag>.boResults，便于后续组件读 ID
        save("$." + tag + ".boResults", boResults);

        // 按顺序对应 cfg.boList 回写
        if (boResults.size() != cfg.getBoList().size()) {
            throw new ServiceException("BO_CREATE 响应 boResults 数量(" + boResults.size()
                    + ")与配置 boList 数量(" + cfg.getBoList().size() + ")不一致");
        }
        int rewriteCount = 0;
        for (int i = 0; i < cfg.getBoList().size(); i++) {
            BoCreateCfg.BoItemCfg itemCfg = cfg.getBoList().get(i);
            BoCreateCfg.RewriteCfg rewrite = itemCfg.getRewrite();
            if (rewrite != null) {
                rewriteCount += applyRewrite(rewrite, boResults.get(i), tag, i);
            }
        }

        long createdCount = boResults.stream()
            .mapToLong(r -> r.get("records") instanceof List<?> records ? records.size() : 0)
            .sum();
        List<String> sampleIds = new ArrayList<>();
        for (Map<String, Object> boResult : boResults) {
            if (boResult.get("records") instanceof List<?> records) {
                for (Object item : records) {
                    if (item instanceof Map<?, ?> record && record.get("ID") != null && sampleIds.size() < 2) {
                        sampleIds.add(String.valueOf(record.get("ID")));
                    }
                }
            }
        }
        StringBuilder summary = new StringBuilder("新建 BO ").append(createdCount).append(" 个");
        if (!sampleIds.isEmpty()) {
            summary.append("：").append(String.join("、", sampleIds));
        }
        if (rewriteCount > 0) {
            summary.append("，回写来源 ").append(rewriteCount).append(" 条");
        }
        resultSummary(summary.toString());

        log.info("[databus] boCreate 完成 tag={} 共 {} 项 BO", tag, boResults.size());
    }

    /**
     * 执行单条 BO 的回写策略（6 策略，与老系统 BoCreateProcessor 一致）。
     *
     * @return 实际回写的记录条数（供步骤摘要统计），未回写返回 0
     */
    @SuppressWarnings("unchecked")
    private int applyRewrite(BoCreateCfg.RewriteCfg rewrite, Map<String, Object> boResult,
                             String tag, int index) {
        String strategy = rewrite.getStrategy();
        if (strategy == null || strategy.isBlank() || "no".equals(strategy)) {
            return 0;
        }
        String path = rewrite.getPath();
        if (path == null || path.isBlank()) {
            log.warn("[databus] boCreate tag={} 第 {} 项策略 {} 缺少 path，跳过回写", tag, index, strategy);
            return 0;
        }
        Object recordsRaw = boResult.get("records");
        if (!(recordsRaw instanceof List<?> rawList)) {
            log.warn("[databus] boCreate tag={} 第 {} 项 records 非 List，跳过回写", tag, index);
            return 0;
        }
        List<Map<String, Object>> records = new ArrayList<>(rawList.size());
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                records.add((Map<String, Object>) map);
            } else {
                log.warn("[databus] boCreate tag={} 第 {} 项 record 非 Map，跳过该条", tag, index);
            }
        }

        switch (strategy) {
            case "all" -> {
                save(path, records);
                return records.size();
            }
            case "boId" -> {
                writeFieldsPerRecord(path, records, List.of("ID"), tag, index);
                return records.size();
            }
            case "add" -> {
                writeFieldsPerRecord(path, records, rewrite.getAddFields(), tag, index);
                return records.size();
            }
            case "exclude" -> {
                save(path, filterRecords(records, rewrite.getExcludes(), false));
                return records.size();
            }
            case "include" -> {
                if (rewrite.getIncludes() == null || rewrite.getIncludes().isEmpty()) {
                    save(path, records);
                } else {
                    save(path, filterRecords(records, rewrite.getIncludes(), true));
                }
                return records.size();
            }
            default -> {
                log.warn("[databus] boCreate tag={} 第 {} 项未知策略 {}，跳过", tag, index, strategy);
                return 0;
            }
        }
    }

    /**
     * add / boId：遍历 records[j]，对 fields 每字段写 {@code $.<path>[j].<field>}。
     * <p>用基类 save（自动建父路径 + 数组扩容），路径拼 {@code <path>[j].<field>}。
     */
    private void writeFieldsPerRecord(String path, List<Map<String, Object>> records,
                                      List<String> fields, String tag, int index) {
        if (fields == null || fields.isEmpty()) {
            log.warn("[databus] boCreate tag={} 第 {} 项 add/boId 策略缺少 addFields", tag, index);
            return;
        }
        for (int j = 0; j < records.size(); j++) {
            Map<String, Object> record = records.get(j);
            for (String field : fields) {
                save(path + "[" + j + "]." + field, record.get(field));
            }
        }
    }

    /**
     * exclude（保留非 excludes 字段）/ include（只保留 includes 字段）公共过滤。
     *
     * @param fields   过滤字段列表
     * @param keepFlag true=include（只保留），false=exclude（剔除）
     */
    private List<Map<String, Object>> filterRecords(List<Map<String, Object>> records,
                                                    List<String> fields, boolean keepFlag) {
        if (fields == null || fields.isEmpty()) {
            return records;
        }
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Map<String, Object> record : records) {
            Map<String, Object> filtered = new LinkedHashMap<>(record.size());
            record.forEach((key, value) -> {
                boolean inFields = fields.contains(key);
                boolean keep = keepFlag ? inFields : !inFields;
                if (keep) {
                    filtered.put(key, value);
                }
            });
            result.add(filtered);
        }
        return result;
    }

    /**
     * 把响应 boResults（List of LinkedHashMap）规整为 {@code List<Map<String, Object>>}。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> convertResults(Object boResultsRaw) {
        return (List<Map<String, Object>>) boResultsRaw;
    }

    /**
     * 字符串参数解析：纯路径读取 / 混合路径替换 / 字面量原样返回，统一转 String。
     */
    private String resolveStr(Object input) {
        if (input == null) {
            return null;
        }
        Object resolved = resolveParam(input);
        return resolved == null ? null : resolved.toString();
    }
}
