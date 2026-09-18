package org.dromara.databus.component.bpm;

import com.jayway.jsonpath.TypeRef;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.BoItem;
import org.dromara.databus.connector.bpm.dto.BoUpdateRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BO_UPDATE 组件（注册名 {@code boUpdate}）。
 * <p>
 * 调 BPM 端 BO_UPDATE 端点按记录 ID 更新 BO 数据。records 每条必须含 ID 字段
 * （可先经 boQuery 查出记录再整体回写）；BPM 端整体事务 all-or-nothing，
 * 任一条失败全部回滚并抛错。
 *
 * <p>响应 {@code {boResults: [{boName, updatedCount}]}} 存入 {@code $.<tag>.boResults}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("boUpdate")
public class BoUpdateComponent extends DatabusNodeComponent {

    private static final String FIELD_ID = "ID";

    private static final TypeRef<List<Map<String, Object>>> RECORDS_TYPE =
            new TypeRef<List<Map<String, Object>>>() {};

    @Override
    public void process() {
        BoUpdateCfg cfg = this.getCmpData(BoUpdateCfg.class);
        if (cfg == null) {
            throw new ServiceException("BO_UPDATE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("BO_UPDATE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("BO_UPDATE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getBoList() == null || cfg.getBoList().isEmpty()) {
            throw new ServiceException("BO_UPDATE 组件缺少 boList 配置（tag=" + tag + "）");
        }

        // 组装 boList：从 sourcePath 读 List<Map> 作为 records，逐条校验 ID
        List<BoItem> boItems = new ArrayList<>(cfg.getBoList().size());
        for (int i = 0; i < cfg.getBoList().size(); i++) {
            BoUpdateCfg.BoItemCfg itemCfg = cfg.getBoList().get(i);
            if (itemCfg == null || itemCfg.getBoName() == null || itemCfg.getBoName().isBlank()) {
                throw new ServiceException("BO_UPDATE 组件 boList[" + i + "] 缺少 boName（tag=" + tag + "）");
            }
            if (itemCfg.getSourcePath() == null || itemCfg.getSourcePath().isBlank()) {
                throw new ServiceException("BO_UPDATE 组件 boList[" + i + "] 缺少 sourcePath（tag=" + tag + "）");
            }
            List<Map<String, Object>> records = get(itemCfg.getSourcePath(), RECORDS_TYPE);
            if (records == null || records.isEmpty()) {
                throw new ServiceException("BO_UPDATE 组件 boList[" + i + "] sourcePath 读到的 records 为空: "
                        + itemCfg.getSourcePath());
            }
            for (int j = 0; j < records.size(); j++) {
                Map<String, Object> record = records.get(j);
                if (record == null || record.get(FIELD_ID) == null) {
                    throw new ServiceException("BO_UPDATE 组件 boList[" + i + "].records[" + j
                            + "] 缺少 ID 字段（按记录 ID 定位更新）");
                }
            }
            BoItem item = new BoItem();
            item.setBoName(itemCfg.getBoName());
            item.setRecords(records);
            boItems.add(item);
        }

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        BoUpdateRequest request = new BoUpdateRequest();
        request.setBoList(boItems);

        Object result = connector.boUpdate(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("BO_UPDATE 响应非 JSON 对象: " + result);
        }
        Object boResultsRaw = resultMap.get("boResults");
        if (!(boResultsRaw instanceof List<?>)) {
            throw new ServiceException("BO_UPDATE 响应 boResults 非 List: " + boResultsRaw);
        }

        // boResults 存到 $.<tag>.boResults（[{boName, updatedCount}]）
        save("$." + tag + ".boResults", boResultsRaw);
        log.info("[databus] boUpdate 完成 tag={} 共 {} 项 BO", tag, ((List<?>) boResultsRaw).size());
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
