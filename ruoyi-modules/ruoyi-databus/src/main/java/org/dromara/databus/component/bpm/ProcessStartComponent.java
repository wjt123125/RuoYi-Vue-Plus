package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.ProcessStartRequest;

import java.util.List;
import java.util.Map;

/**
 * PROCESS_START 组件（注册名 {@code processStart}）。
 * <p>
 * 调 BPM 端 PROCESS_START 端点启动流程。响应 {@code {processInstanceId, isProcess, activeTaskIds}}
 * 按字段平铺写入数据空间 {@code $.<tag>.*}。
 *
 * <p>title 含 {@code ${$.xxx}} 模板时由本组件调 {@code getDatabusContext().resolveMixedPath(...)}
 * 替换为纯字符串后传 BPM 端（决策 9.1.6）。BPM 端不做 autocomplete（决策 9.1.1：task_complete 独立端点）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("processStart")
public class ProcessStartComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        ProcessStartCfg cfg = this.getCmpData(ProcessStartCfg.class);
        if (cfg == null) {
            throw new ServiceException("PROCESS_START 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("PROCESS_START 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("PROCESS_START 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getProcessDefId() == null || cfg.getProcessDefId().isBlank()) {
            throw new ServiceException("PROCESS_START 组件缺少 processDefId 配置（tag=" + tag + "）");
        }
        if (cfg.getUid() == null || cfg.getUid().isBlank()) {
            throw new ServiceException("PROCESS_START 组件缺少 uid 配置（tag=" + tag + "）");
        }
        if (cfg.getTitle() == null || cfg.getTitle().isBlank()) {
            throw new ServiceException("PROCESS_START 组件缺少 title 配置（tag=" + tag + "）");
        }

        // 参数解析：connectionId/processDefId/uid 支持裸路径 / 混合字符串 / 字面量
        String connectionId = resolveStr(cfg.getConnectionId());
        String processDefId = resolveStr(cfg.getProcessDefId());
        String uid = resolveStr(cfg.getUid());
        // title 用 resolveMixedPath 替换 ${$.xxx} 片段为纯字符串（决策 9.1.6）
        String title = getDatabusContext().resolveMixedPath(cfg.getTitle());

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(connectionId);

        ProcessStartRequest request = new ProcessStartRequest();
        request.setProcessDefId(processDefId);
        request.setUid(uid);
        request.setTitle(title);

        Object result = connector.processStart(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("PROCESS_START 响应非 JSON 对象: " + result);
        }
        // 平铺 processInstanceId / isProcess / activeTaskIds
        resultMap.forEach((key, value) -> save("$." + tag + "." + key, value));
        Object activeTaskIds = resultMap.get("activeTaskIds");
        String summaryText = "流程：" + resultMap.get("processInstanceId");
        if (activeTaskIds instanceof List<?> tasks && !tasks.isEmpty()) {
            summaryText += "，待办 " + tasks.size() + " 个";
        }
        resultSummary(summaryText);
        log.info("[databus] processStart 完成 tag={} processInstanceId={} isProcess={}",
                tag, resultMap.get("processInstanceId"), resultMap.get("isProcess"));
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
