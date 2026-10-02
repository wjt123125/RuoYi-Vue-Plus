package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.component.cfg.ProcessStartCfg;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
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
 * <p>title 含 {@code {{ $.xxx }}} 表达式时由本组件调 {@code getDatabusContext().resolveMixedPath(...)}
 * 求值拼接为字符串后传 BPM 端。BPM 端不做 autocomplete（决策 9.1.1：task_complete 独立端点）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("processStart")
@DatabusCmp(
    code = "processStart", name = "BPM 启流程", shortName = "启流程",
    icon = "ph:rocket", color = "#e6a23c",
    description = "启动 BPM 流程实例，title 支持 {{ $.xxx }} 表达式，响应平铺到 $.数据空间（含 processInstanceId 供下游 boCreate.bindId 引用）",
    cfg = ProcessStartCfg.class, sort = 110
)
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

        // 参数解析：connectionId/processDefId/uid 经 resolve 求值（{{ $.路径 }} 或字面量）
        String connectionId = resolveStr(cfg.getConnectionId());
        String processDefId = resolveStr(cfg.getProcessDefId());
        String uid = resolveStr(cfg.getUid());
        // title 用 resolveMixedPath 求值 {{ }} 片段后拼接为字符串（无标记时原样）
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
     * 字符串参数解析：resolve 求值后统一转 String。
     */
    private String resolveStr(Object input) {
        if (input == null) {
            return null;
        }
        Object resolved = resolveParam(input);
        return resolved == null ? null : resolved.toString();
    }
}
