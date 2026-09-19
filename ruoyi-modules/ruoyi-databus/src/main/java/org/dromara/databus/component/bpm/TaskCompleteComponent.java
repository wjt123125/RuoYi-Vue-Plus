package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.TaskCompleteRequest;

import java.util.List;
import java.util.Map;

/**
 * TASK_COMPLETE 组件（注册名 {@code taskComplete}）。
 * <p>
 * 调 BPM 端 TASK_COMPLETE 端点提交流程实例的所有活跃任务。响应
 * {@code {processInstanceId, processEnded, completedTaskIds, failedTaskIds, failedErrors}}
 * 按字段平铺写入数据空间 {@code $.<tag>.*}。
 *
 * <p>BPM 端 code 始终 0（决策 9.1.1：部分失败语义由 Connector/Component 翻译）。本组件
 * 拿到响应后看 {@code failedTaskIds}：非空 + cfg.failOnError=true 抛 ServiceException 中断；
 * 非空 + cfg.failOnError=false 仅 log.warn 不抛，调用方可在后续节点读
 * {@code $.<tag>.failedTaskIds} 自行决策。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("taskComplete")
public class TaskCompleteComponent extends DatabusNodeComponent {

    @Override
    public void process() {
        TaskCompleteCfg cfg = this.getCmpData(TaskCompleteCfg.class);
        if (cfg == null) {
            throw new ServiceException("TASK_COMPLETE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("TASK_COMPLETE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("TASK_COMPLETE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getProcessInstanceId() == null || cfg.getProcessInstanceId().isBlank()) {
            throw new ServiceException("TASK_COMPLETE 组件缺少 processInstanceId 配置（tag=" + tag + "）");
        }
        if (cfg.getUid() == null || cfg.getUid().isBlank()) {
            throw new ServiceException("TASK_COMPLETE 组件缺少 uid 配置（tag=" + tag + "）");
        }

        // 参数解析：connectionId/processInstanceId/uid 支持裸路径 / 混合字符串 / 字面量
        String connectionId = resolveStr(cfg.getConnectionId());
        String processInstanceId = resolveStr(cfg.getProcessInstanceId());
        String uid = resolveStr(cfg.getUid());
        boolean failOnError = Boolean.TRUE.equals(cfg.getFailOnError());

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(connectionId);

        TaskCompleteRequest request = new TaskCompleteRequest();
        request.setProcessInstanceId(processInstanceId);
        request.setUid(uid);

        Object result = connector.taskComplete(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("TASK_COMPLETE 响应非 JSON 对象: " + result);
        }
        // 平铺 processInstanceId / processEnded / completedTaskIds / failedTaskIds / failedErrors
        resultMap.forEach((key, value) -> save("$." + tag + "." + key, value));

        int completedCount = resultMap.get("completedTaskIds") instanceof List<?> completedList
                ? completedList.size() : 0;
        int failedCount = resultMap.get("failedTaskIds") instanceof List<?> failedIdList
                ? failedIdList.size() : 0;
        String taskSummary = "任务提交：成功 " + completedCount + " 个";
        if (failedCount > 0) {
            taskSummary += "，失败 " + failedCount + " 个";
        }
        if (Boolean.TRUE.equals(resultMap.get("processEnded"))) {
            taskSummary += "，流程已结束";
        }
        resultSummary(taskSummary);

        // 部分失败语义翻译（决策 9.1.3）
        Object failedTaskIdsRaw = resultMap.get("failedTaskIds");
        if (failedTaskIdsRaw instanceof List<?> failedList && !failedList.isEmpty()) {
            String errMsg = "TASK_COMPLETE 部分任务失败 tag=" + tag
                    + " failedTaskIds=" + failedList
                    + " completedTaskIds=" + resultMap.get("completedTaskIds");
            if (failOnError) {
                throw new ServiceException(errMsg);
            }
            log.warn("[databus] {}", errMsg);
        }
        log.info("[databus] taskComplete 完成 tag={} processEnded={}",
                tag, resultMap.get("processEnded"));
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
