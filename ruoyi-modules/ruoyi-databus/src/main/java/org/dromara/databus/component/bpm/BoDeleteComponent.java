package org.dromara.databus.component.bpm;

import com.jayway.jsonpath.TypeRef;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.BoDeleteRequest;
import org.dromara.databus.connector.bpm.dto.BoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BO_DELETE 组件（注册名 {@code boDelete}）。
 * <p>
 * 调 BPM 端 BO_DELETE 端点删除 BO 数据。两种模式：
 * method=remove（默认）按记录 ID 逐条删除；method=removeByBindId 按流程实例 ID
 * 批量删除该流程下所有 BO 数据。BPM 端整体事务 all-or-nothing。
 *
 * <p>响应 {@code {boResults: [{boName, removedCount}]}} 存入 {@code $.<tag>.boResults}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("boDelete")
public class BoDeleteComponent extends DatabusNodeComponent {

    private static final String DEFAULT_METHOD = "remove";
    private static final String METHOD_REMOVE = "remove";
    private static final String METHOD_REMOVE_BY_BIND_ID = "removeByBindId";

    private static final String FIELD_ID = "ID";
    private static final String FIELD_BIND_ID = "BINDID";

    private static final TypeRef<List<Map<String, Object>>> RECORDS_TYPE =
            new TypeRef<List<Map<String, Object>>>() {};

    @Override
    public void process() {
        BoDeleteCfg cfg = this.getCmpData(BoDeleteCfg.class);
        if (cfg == null) {
            throw new ServiceException("BO_DELETE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("BO_DELETE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("BO_DELETE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        String method = cfg.getMethod() == null || cfg.getMethod().isBlank()
                ? DEFAULT_METHOD : resolveStr(cfg.getMethod());
        if (!METHOD_REMOVE.equals(method) && !METHOD_REMOVE_BY_BIND_ID.equals(method)) {
            throw new ServiceException("BO_DELETE 组件 method 仅支持 remove / removeByBindId，实际: " + method);
        }
        if (cfg.getBoList() == null || cfg.getBoList().isEmpty()) {
            throw new ServiceException("BO_DELETE 组件缺少 boList 配置（tag=" + tag + "）");
        }

        // 组装 boList：从 sourcePath 读 List<Map> 作为 records，按 method 校验必需字段
        String requiredField = METHOD_REMOVE_BY_BIND_ID.equals(method) ? FIELD_BIND_ID : FIELD_ID;
        List<BoItem> boItems = new ArrayList<>(cfg.getBoList().size());
        for (int i = 0; i < cfg.getBoList().size(); i++) {
            BoDeleteCfg.BoItemCfg itemCfg = cfg.getBoList().get(i);
            if (itemCfg == null || itemCfg.getBoName() == null || itemCfg.getBoName().isBlank()) {
                throw new ServiceException("BO_DELETE 组件 boList[" + i + "] 缺少 boName（tag=" + tag + "）");
            }
            if (itemCfg.getSourcePath() == null || itemCfg.getSourcePath().isBlank()) {
                throw new ServiceException("BO_DELETE 组件 boList[" + i + "] 缺少 sourcePath（tag=" + tag + "）");
            }
            List<Map<String, Object>> records = get(itemCfg.getSourcePath(), RECORDS_TYPE);
            if (records == null || records.isEmpty()) {
                throw new ServiceException("BO_DELETE 组件 boList[" + i + "] sourcePath 读到的 records 为空: "
                        + itemCfg.getSourcePath());
            }
            for (int j = 0; j < records.size(); j++) {
                Map<String, Object> record = records.get(j);
                if (record == null || record.get(requiredField) == null) {
                    throw new ServiceException("BO_DELETE 组件 boList[" + i + "].records[" + j
                            + "] 缺少 " + requiredField + " 字段（method=" + method + "）");
                }
            }
            BoItem item = new BoItem();
            item.setBoName(itemCfg.getBoName());
            item.setRecords(records);
            boItems.add(item);
        }

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        BoDeleteRequest request = new BoDeleteRequest();
        request.setMethod(method);
        request.setBoList(boItems);

        Object result = connector.boDelete(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("BO_DELETE 响应非 JSON 对象: " + result);
        }
        Object boResultsRaw = resultMap.get("boResults");
        if (!(boResultsRaw instanceof List<?>)) {
            throw new ServiceException("BO_DELETE 响应 boResults 非 List: " + boResultsRaw);
        }

        // boResults 存到 $.<tag>.boResults（[{boName, removedCount}]）
        save("$." + tag + ".boResults", boResultsRaw);
        log.info("[databus] boDelete 完成 tag={} method={} 共 {} 项 BO", tag, method, ((List<?>) boResultsRaw).size());
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
