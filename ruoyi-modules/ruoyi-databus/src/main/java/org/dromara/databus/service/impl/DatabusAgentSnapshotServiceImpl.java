package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.agent.AgentChainSnapshot;
import org.dromara.databus.agent.AgentGuard;
import org.dromara.databus.domain.DatabusAgentSnapshot;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.mapper.DatabusAgentSnapshotMapper;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI agent 链路快照 Service 实现。
 *
 * @author databus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabusAgentSnapshotServiceImpl implements IDatabusAgentSnapshotService {

    /**
     * 动手前快照的操作类型：修改 / 新建。
     */
    private static final String OPERATION_UPDATE = "UPDATE";
    private static final String OPERATION_CREATE = "CREATE";

    private final DatabusAgentSnapshotMapper snapshotMapper;

    private final AgentGuard agentGuard;

    @Override
    public AgentChainSnapshot record(String runId, int round, DatabusChainVo before) {
        AgentChainSnapshot payload = new AgentChainSnapshot();
        payload.setChainId(before.getId());
        payload.setOperation(OPERATION_UPDATE);
        payload.setChainCode(before.getChainCode());
        payload.setChainName(before.getChainName());
        payload.setCmpProperty(before.getCmpProperty());
        payload.setElExpression(before.getElExpression());
        payload.setCanvasData(before.getCanvasData());
        payload.setInputParams(before.getInputParams());
        payload.setLogLevel(before.getLogLevel());

        String json = JsonUtils.toJsonString(payload);
        DatabusAgentSnapshot row = new DatabusAgentSnapshot();
        row.setRunId(runId);
        row.setRound(round);
        row.setOperation(OPERATION_UPDATE);
        row.setPayload(json);
        snapshotMapper.insert(row);
        agentGuard.updateBeforePayload(runId, json);
        return payload;
    }

    @Override
    public AgentChainSnapshot recordCreate(String runId, int round, Long chainId, String chainCode, String chainName) {
        AgentChainSnapshot payload = new AgentChainSnapshot();
        payload.setChainId(chainId);
        payload.setOperation(OPERATION_CREATE);
        payload.setChainCode(chainCode);
        payload.setChainName(chainName);

        DatabusAgentSnapshot row = new DatabusAgentSnapshot();
        row.setRunId(runId);
        row.setRound(round);
        row.setOperation(OPERATION_CREATE);
        row.setPayload(JsonUtils.toJsonString(payload));
        snapshotMapper.insert(row);
        return payload;
    }

    @Override
    public List<AgentChainSnapshot> listEarliestByRun(String runId) {
        List<DatabusAgentSnapshot> rows = snapshotMapper.selectList(Wrappers.<DatabusAgentSnapshot>lambdaQuery()
            .eq(DatabusAgentSnapshot::getRunId, runId)
            .orderByAsc(DatabusAgentSnapshot::getRound));
        Map<Long, AgentChainSnapshot> earliest = new LinkedHashMap<>();
        for (DatabusAgentSnapshot row : rows) {
            AgentChainSnapshot payload = parse(row.getPayload());
            if (payload == null || payload.getChainId() == null) {
                continue;
            }
            if (payload.getOperation() == null) {
                payload.setOperation(row.getOperation());
            }
            earliest.putIfAbsent(payload.getChainId(), payload);
        }
        return new ArrayList<>(earliest.values());
    }

    private AgentChainSnapshot parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payload, AgentChainSnapshot.class);
        } catch (Exception e) {
            log.warn("[databus-agent] 快照载荷解析失败，已跳过：{}", e.getMessage());
            return null;
        }
    }

}