package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.agent.run.AgentChainSnapshot;
import org.dromara.databus.agent.run.AgentComponentDraftSnapshot;
import org.dromara.databus.agent.support.AgentGuard;
import org.dromara.databus.domain.DatabusAgentSnapshot;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.mapper.DatabusAgentSnapshotMapper;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.dromara.databus.constant.AgentConstants.OPERATION_CREATE;
import static org.dromara.databus.constant.AgentConstants.OPERATION_UPDATE;
import static org.dromara.databus.constant.AgentConstants.SNAPSHOT_RESOURCE_CHAIN;
import static org.dromara.databus.constant.AgentConstants.SNAPSHOT_RESOURCE_COMPONENT_DRAFT;

/**
 * AI agent 改动快照 Service 实现：payload 按 resourceType 区分链路与组件草稿。
 *
 * @author databus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabusAgentSnapshotServiceImpl implements IDatabusAgentSnapshotService {

    private final DatabusAgentSnapshotMapper snapshotMapper;

    private final AgentGuard agentGuard;

    @Override
    public AgentChainSnapshot record(String runId, int round, DatabusChainVo before) {
        AgentChainSnapshot payload = new AgentChainSnapshot();
        payload.setResourceType(SNAPSHOT_RESOURCE_CHAIN);
        payload.setChainId(before.getId());
        payload.setOperation(OPERATION_UPDATE);
        payload.setChainCode(before.getChainCode());
        payload.setChainName(before.getChainName());
        payload.setCmpProperty(before.getCmpProperty());
        payload.setElExpression(before.getElExpression());
        payload.setCanvasData(before.getCanvasData());
        payload.setInputParams(before.getInputParams());
        payload.setLogLevel(before.getLogLevel());
        payload.setRemark(before.getRemark());

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
        payload.setResourceType(SNAPSHOT_RESOURCE_CHAIN);
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
    public AgentComponentDraftSnapshot recordDraft(String runId, int round, DatabusComponentVo before) {
        AgentComponentDraftSnapshot payload = new AgentComponentDraftSnapshot();
        payload.setResourceType(SNAPSHOT_RESOURCE_COMPONENT_DRAFT);
        payload.setComponentId(before.getId());
        payload.setOperation(OPERATION_UPDATE);
        payload.setComponentCode(before.getComponentCode());
        payload.setComponentName(before.getComponentName());
        payload.setCategory(before.getCategory());
        payload.setGroupName(before.getGroupName());
        payload.setDescription(before.getDescription());
        payload.setScriptBody(before.getScriptBody());
        payload.setInputParams(before.getInputParams());

        DatabusAgentSnapshot row = new DatabusAgentSnapshot();
        row.setRunId(runId);
        row.setRound(round);
        row.setOperation(OPERATION_UPDATE);
        row.setPayload(JsonUtils.toJsonString(payload));
        snapshotMapper.insert(row);
        return payload;
    }

    @Override
    public AgentComponentDraftSnapshot recordDraftCreate(String runId, int round, Long componentId,
                                                         String componentCode, String componentName) {
        AgentComponentDraftSnapshot payload = new AgentComponentDraftSnapshot();
        payload.setResourceType(SNAPSHOT_RESOURCE_COMPONENT_DRAFT);
        payload.setComponentId(componentId);
        payload.setOperation(OPERATION_CREATE);
        payload.setComponentCode(componentCode);
        payload.setComponentName(componentName);

        DatabusAgentSnapshot row = new DatabusAgentSnapshot();
        row.setRunId(runId);
        row.setRound(round);
        row.setOperation(OPERATION_CREATE);
        row.setPayload(JsonUtils.toJsonString(payload));
        snapshotMapper.insert(row);
        return payload;
    }

    @Override
    public List<AgentChainSnapshot> listEarliestChainsByRun(String runId) {
        Map<Long, AgentChainSnapshot> earliest = new LinkedHashMap<>();
        for (DatabusAgentSnapshot row : listRows(runId)) {
            AgentChainSnapshot payload = parse(row.getPayload(), AgentChainSnapshot.class);
            if (payload == null || payload.getChainId() == null) {
                continue;
            }
            if (!SNAPSHOT_RESOURCE_CHAIN.equals(payload.getResourceType())) {
                continue;
            }
            earliest.putIfAbsent(payload.getChainId(), payload);
        }
        return new ArrayList<>(earliest.values());
    }

    @Override
    public List<AgentComponentDraftSnapshot> listEarliestDraftsByRun(String runId) {
        Map<Long, AgentComponentDraftSnapshot> earliest = new LinkedHashMap<>();
        for (DatabusAgentSnapshot row : listRows(runId)) {
            AgentComponentDraftSnapshot payload = parse(row.getPayload(), AgentComponentDraftSnapshot.class);
            if (payload == null || payload.getComponentId() == null) {
                continue;
            }
            if (!SNAPSHOT_RESOURCE_COMPONENT_DRAFT.equals(payload.getResourceType())) {
                continue;
            }
            earliest.putIfAbsent(payload.getComponentId(), payload);
        }
        return new ArrayList<>(earliest.values());
    }

    private List<DatabusAgentSnapshot> listRows(String runId) {
        return snapshotMapper.selectList(Wrappers.<DatabusAgentSnapshot>lambdaQuery()
            .eq(DatabusAgentSnapshot::getRunId, runId)
            .orderByAsc(DatabusAgentSnapshot::getRound)
            .orderByAsc(DatabusAgentSnapshot::getId));
    }

    @Override
    public Set<String> changedRunIds(Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return Set.of();
        }
        List<Object> values = snapshotMapper.selectObjs(Wrappers.<DatabusAgentSnapshot>query()
            .select("DISTINCT run_id")
            .in("run_id", runIds));
        Set<String> changed = new HashSet<>();
        for (Object value : values) {
            if (value != null) {
                changed.add(String.valueOf(value));
            }
        }
        return changed;
    }

    @Override
    public boolean hasChanges(String runId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        return snapshotMapper.selectCount(Wrappers.<DatabusAgentSnapshot>lambdaQuery()
            .eq(DatabusAgentSnapshot::getRunId, runId)) > 0;
    }

    private <T> T parse(String payload, Class<T> type) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return JsonUtils.parseObject(payload, type);
        } catch (Exception e) {
            log.warn("[databus-agent] 快照载荷解析失败，已跳过：{}", e.getMessage());
            return null;
        }
    }

}