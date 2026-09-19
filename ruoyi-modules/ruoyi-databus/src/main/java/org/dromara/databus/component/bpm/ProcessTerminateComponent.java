package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.ProcessTerminateRequest;

import java.util.Map;

/**
 * PROCESS_TERMINATE 组件（注册名 {@code processTerminate}）。
 * <p>
 * 调 BPM 端 PROCESS_TERMINATE 端点终止流程实例。BPM 端先查流程实例校验存在性
 * （不存在抛错，规避老系统静默返回）；已结束时不重复终止，返回 terminated=false
 * + alreadyEnded=true（幂等语义，不报错）。
 *
 * <p>响应 {@code {processInstanceId, terminated, alreadyEnded}} 存入 {@code $.<tag>.result}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("processTerminate")
public class ProcessTerminateComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        ProcessTerminateCfg cfg = this.getCmpData(ProcessTerminateCfg.class);
        if (cfg == null) {
            throw new ServiceException("PROCESS_TERMINATE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("PROCESS_TERMINATE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("PROCESS_TERMINATE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        String instanceId = resolveStr(cfg.getInstanceId());
        if (instanceId == null || instanceId.isBlank()) {
            throw new ServiceException("PROCESS_TERMINATE 组件缺少 instanceId 配置（tag=" + tag + "）");
        }
        String userId = resolveStr(cfg.getUserId());
        if (userId == null || userId.isBlank()) {
            throw new ServiceException("PROCESS_TERMINATE 组件缺少 userId 配置（tag=" + tag + "）");
        }

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        ProcessTerminateRequest request = new ProcessTerminateRequest();
        request.setInstanceId(instanceId);
        request.setUserId(userId);

        Object result = connector.processTerminate(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("PROCESS_TERMINATE 响应非 JSON 对象: " + result);
        }

        // 响应存到 $.<tag>.result（{processInstanceId, terminated, alreadyEnded}）
        save("$." + tag + ".result", resultMap);
        if (Boolean.TRUE.equals(resultMap.get("terminated"))) {
            resultSummary("终止流程：" + instanceId);
        } else if (Boolean.TRUE.equals(resultMap.get("alreadyEnded"))) {
            resultSummary("流程此前已结束：" + instanceId);
        } else {
            resultSummary("终止未生效：" + instanceId);
        }
        log.info("[databus] processTerminate 完成 tag={} instanceId={} terminated={}",
                tag, instanceId, resultMap.get("terminated"));
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
