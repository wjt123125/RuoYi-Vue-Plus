package org.dromara.databus.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusAgentSnapshot;
import org.dromara.databus.domain.vo.AgentSnapshotVo;

/**
 * AI agent 快照 Mapper（只增不改，无逻辑删除）
 *
 * @author databus
 */
public interface DatabusAgentSnapshotMapper extends BaseMapperPlus<DatabusAgentSnapshot, AgentSnapshotVo> {

}