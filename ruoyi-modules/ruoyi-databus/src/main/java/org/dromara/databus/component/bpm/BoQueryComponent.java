package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.BoQueryRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BO_QUERY 组件（注册名 {@code boQuery}）。
 * <p>
 * 调 BPM 端 BO_QUERY 端点查询 BO 数据。支持 list / listPage / count 三种查询方法、
 * maxRecord 影响量前置校验、动态查询条件（conditionSourcePath 从数据空间读取）、
 * 关联表（mainField=relField）与子表（BINDID=主表 BINDID）数据挂载。
 *
 * <p>响应存 {@code $.<tag>.boName / $.<tag>.method / $.<tag>.records}（list/listPage）
 * 或 {@code $.<tag>.count}（count）；records 内嵌关联表/子表数据（键为对应 BO 名称）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("boQuery")
public class BoQueryComponent extends DatabusNodeComponent {

    private static final String DEFAULT_METHOD = "list";
    private static final String METHOD_LIST = "list";
    private static final String METHOD_LIST_PAGE = "listPage";
    private static final String METHOD_COUNT = "count";

    @Override
    public void process() {
        BoQueryCfg cfg = this.getCmpData(BoQueryCfg.class);
        if (cfg == null) {
            throw new ServiceException("BO_QUERY 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("BO_QUERY 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("BO_QUERY 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getMain() == null) {
            throw new ServiceException("BO_QUERY 组件缺少 main 配置（tag=" + tag + "）");
        }
        BoQueryCfg.MainCfg mainCfg = cfg.getMain();
        if (mainCfg.getBoName() == null || mainCfg.getBoName().isBlank()) {
            throw new ServiceException("BO_QUERY 组件缺少 main.boName 配置（tag=" + tag + "）");
        }
        String method = mainCfg.getMethod() == null || mainCfg.getMethod().isBlank()
                ? DEFAULT_METHOD : resolveStr(mainCfg.getMethod());
        if (!METHOD_LIST.equals(method) && !METHOD_LIST_PAGE.equals(method) && !METHOD_COUNT.equals(method)) {
            throw new ServiceException("BO_QUERY 组件 main.method 仅支持 list / listPage / count，实际: " + method);
        }
        if (METHOD_LIST_PAGE.equals(method)
                && (mainCfg.getFirstRow() == null || mainCfg.getRowCount() == null)) {
            throw new ServiceException("BO_QUERY 组件 method=listPage 时 main.firstRow / main.rowCount 必填（tag=" + tag + "）");
        }

        // 组装请求
        BoQueryRequest request = new BoQueryRequest();
        BoQueryRequest.MainQuery main = new BoQueryRequest.MainQuery();
        main.setBoName(resolveStr(mainCfg.getBoName()));
        main.setMethod(method);
        main.setMaxRecord(mainCfg.getMaxRecord());
        main.setFirstRow(mainCfg.getFirstRow());
        main.setRowCount(mainCfg.getRowCount());
        main.setConditions(readConditions(mainCfg.getConditionSourcePath()));
        request.setMain(main);
        if (cfg.getRelate() != null && !cfg.getRelate().isEmpty()) {
            List<BoQueryRequest.RelateQuery> relateList = new ArrayList<>(cfg.getRelate().size());
            for (int i = 0; i < cfg.getRelate().size(); i++) {
                BoQueryCfg.RelateCfg relateCfg = cfg.getRelate().get(i);
                if (relateCfg == null || relateCfg.getBoName() == null || relateCfg.getBoName().isBlank()
                        || relateCfg.getMainField() == null || relateCfg.getMainField().isBlank()
                        || relateCfg.getRelField() == null || relateCfg.getRelField().isBlank()) {
                    throw new ServiceException("BO_QUERY 组件 relate[" + i + "] 缺少 boName / mainField / relField（tag=" + tag + "）");
                }
                BoQueryRequest.RelateQuery relate = new BoQueryRequest.RelateQuery();
                relate.setBoName(relateCfg.getBoName());
                relate.setMainField(relateCfg.getMainField());
                relate.setRelField(relateCfg.getRelField());
                relateList.add(relate);
            }
            request.setRelate(relateList);
        }
        request.setSub(cfg.getSub());

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        Object result = connector.boQuery(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("BO_QUERY 响应非 JSON 对象: " + result);
        }

        // 响应存数据空间：boName / method 固定，list/listPage 存 records，count 存 count
        save("$." + tag + ".boName", resultMap.get("boName"));
        save("$." + tag + ".method", resultMap.get("method"));
        if (METHOD_COUNT.equals(method)) {
            Object count = resultMap.get("count");
            if (count == null) {
                throw new ServiceException("BO_QUERY 响应缺少 count 字段: " + result);
            }
            save("$." + tag + ".count", count);
        } else {
            Object records = resultMap.get("records");
            if (!(records instanceof List<?>)) {
                throw new ServiceException("BO_QUERY 响应 records 非 List: " + records);
            }
            save("$." + tag + ".records", records);
        }
        log.info("[databus] boQuery 完成 tag={} boName={} method={}", tag, main.getBoName(), method);
    }

    /**
     * 从数据空间读取条件列表并映射为 QueryCondition（List&lt;Map&gt; → 强类型）。
     * 条件可为空 / 路径不存在返回 null（BPM 端按无条件查询）。
     */
    private List<BoQueryRequest.QueryCondition> readConditions(String conditionSourcePath) {
        if (conditionSourcePath == null || conditionSourcePath.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rawList = getOptional(conditionSourcePath);
        if (rawList == null) {
            return null;
        }
        List<BoQueryRequest.QueryCondition> conditions = new ArrayList<>(rawList.size());
        for (int i = 0; i < rawList.size(); i++) {
            Map<String, Object> raw = rawList.get(i);
            if (raw == null || raw.get("fieldName") == null || raw.get("operator") == null) {
                throw new ServiceException("BO_QUERY 组件条件[" + i + "] 缺少 fieldName / operator: " + raw);
            }
            BoQueryRequest.QueryCondition condition = new BoQueryRequest.QueryCondition();
            condition.setFieldName(String.valueOf(raw.get("fieldName")));
            condition.setOperator(String.valueOf(raw.get("operator")));
            condition.setParamValue(raw.get("paramValue"));
            Object valid = raw.get("valid");
            condition.setValid(valid == null ? null : Boolean.parseBoolean(valid.toString()));
            conditions.add(condition);
        }
        return conditions;
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
