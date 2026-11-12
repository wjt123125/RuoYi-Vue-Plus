package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.vo.AgentRunVo;

/**
 * AI agent 任务 Mapper
 * <p>
 * seq 号源靠条件 UPDATE 原子自增（InnoDB 行锁），不用 max(seq)+1（并发必撞唯一键）。
 *
 * @author databus
 */
public interface DatabusAgentRunMapper extends BaseMapperPlus<DatabusAgentRun, AgentRunVo> {

    /**
     * seq 号源原子自增。
     *
     * @param runId run 句柄
     * @return 影响行数
     */
    @Update("update databus_agent_run set next_seq = next_seq + 1 where run_id = #{runId}")
    int increaseNextSeq(@Param("runId") String runId);

    /**
     * 读回自增后的 seq 号源（须与 {@link #increaseNextSeq} 同一事务，靠行锁保序）。
     *
     * @param runId run 句柄
     * @return 自增后的 next_seq
     */
    @Select("select next_seq from databus_agent_run where run_id = #{runId}")
    Integer selectNextSeq(@Param("runId") String runId);

}