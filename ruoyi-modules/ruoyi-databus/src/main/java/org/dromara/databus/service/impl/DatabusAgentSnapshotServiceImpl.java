package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.agent.AgentChainSnapshot;
import org.dromara.databus.agent.AgentGuard;
import org.dromara.databus.domain.DatabusAgentSnapshot;
import org.dromara.databus.domain.vo.AgentSnapshotVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.mapper.DatabusAgentSnapshotMapper;
import org.dromara.databus.service.IDatabusAgentSnapshotService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI agent 链路快照 Service 实现。
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
        payload.setChainId(before.getId());
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
        row.setPayload(json);
        snapshotMapper.insert(row);
        agentGuard.updateBeforePayload(runId, json);
        return payload;
    }

    @Override
    public List<AgentSnapshotVo> listByRunId(String runId) {
        List<DatabusAgentSnapshot> rows = snapshotMapper.selectList(Wrappers.<DatabusAgentSnapshot>lambdaQuery()
            .eq(DatabusAgentSnapshot::getRunId, runId)
            .orderByAsc(DatabusAgentSnapshot::getRound));
        List<AgentSnapshotVo> result = new ArrayList<>(rows.size());
        for (DatabusAgentSnapshot row : rows) {
            AgentSnapshotVo vo = new AgentSnapshotVo();
            vo.setId(row.getId());
            vo.setRunId(row.getRunId());
            vo.setRound(row.getRound());
            vo.setCreateTime(row.getCreateTime());
            AgentChainSnapshot payload = parse(row.getPayload());
            if (payload != null) {
                vo.setChainId(payload.getChainId());
                vo.setChainName(payload.getChainName());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public AgentChainSnapshot find(String runId, Integer round) {
        DatabusAgentSnapshot row = snapshotMapper.selectOne(Wrappers.<DatabusAgentSnapshot>lambdaQuery()
            .eq(DatabusAgentSnapshot::getRunId, runId)
            .eq(DatabusAgentSnapshot::getRound, round));
        return row == null ? null : parse(row.getPayload());
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