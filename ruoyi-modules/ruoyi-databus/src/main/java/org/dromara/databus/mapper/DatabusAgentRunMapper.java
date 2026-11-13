package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusAgentRun;
import org.dromara.databus.domain.vo.AgentConversationVo;
import org.dromara.databus.domain.vo.AgentRunVo;

import java.util.List;

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

    /**
     * 取本 run 最后一条带链路的消息所指向的链路 id（同一会话可先后改多条链）。
     *
     * @param runId run 句柄
     * @return 链路 id，没有则 null
     */
    @Select("select chain_id from databus_agent_message where run_id = #{runId} and chain_id is not null order by seq desc limit 1")
    Long selectLastChainId(@Param("runId") String runId);

    /**
     * 按会话聚合出当前用户的会话列表（最近活跃优先）。
     * <p>
     * 会话不建独立表：分组取最近 run 时间、是否在跑、run 条数；
     * 标题取同会话最早一条 run 的 intent，服务层清洗后再交给前端按栏宽省略
     * （相关子查询，命中 idx_agent_run_user_conv_time）。
     *
     * @param userId 发起人
     * @param limit  条数上限
     * @return 会话摘要，按最近活跃倒序
     */
    @Select("select g.conversation_id,"
        + " (select left(r2.intent, 200) from databus_agent_run r2"
        + "   where r2.user_id = g.user_id and r2.conversation_id = g.conversation_id"
        + "     and r2.status <> 'SUPERSEDED' and r2.intent is not null and r2.del_flag = '0'"
        + "   order by r2.create_time asc limit 1) as title,"
        + " g.last_time, g.running, g.run_count"
        + " from (select user_id, conversation_id, max(create_time) as last_time,"
        + "     max(status = 'RUNNING') as running, count(*) as run_count"
        + "   from databus_agent_run"
        + "   where user_id = #{userId} and conversation_id is not null"
        + "     and status <> 'SUPERSEDED' and del_flag = '0'"
        + "   group by user_id, conversation_id"
        + "   order by last_time desc limit #{limit}) g"
        + " order by g.last_time desc")
    List<AgentConversationVo> selectConversations(@Param("userId") Long userId, @Param("limit") int limit);

}