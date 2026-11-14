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
     * 按会话聚合出当前用户的会话列表（置顶优先，其次最近活跃优先）。
     * <p>
     * 会话不建独立表：分组取最近 run 时间、是否在跑、run 条数；
     * 标题取 meta 里的自定义标题，没有则回落同会话最早一条 run 的 intent，
     * 服务层清洗后再交给前端按栏宽省略（相关子查询，命中 idx_agent_run_user_conv_time）。
     * 软删只作用于 meta 行：meta 不存在照常显示，存在且 del_flag='1' 才过滤掉。
     *
     * @param userId 发起人
     * @param limit  条数上限
     * @return 会话摘要，按置顶 + 最近活跃倒序
     */
    @Select("select g.conversation_id,"
        + " coalesce(m.custom_title,"
        + "   (select left(r2.intent, 200) from databus_agent_run r2"
        + "     where r2.user_id = g.user_id and r2.conversation_id = g.conversation_id"
        + "       and r2.status <> 'SUPERSEDED' and r2.intent is not null and r2.del_flag = '0'"
        + "     order by r2.create_time asc limit 1)) as title,"
        + " g.last_time, g.running, g.run_count,"
        + " (case when m.pinned = '1' then 1 else 0 end) as pinned, m.workspace_id"
        + " from (select user_id, conversation_id, max(create_time) as last_time,"
        + "     max(status = 'RUNNING') as running, count(*) as run_count"
        + "   from databus_agent_run"
        + "   where user_id = #{userId} and conversation_id is not null"
        + "     and status <> 'SUPERSEDED' and del_flag = '0'"
        + "   group by user_id, conversation_id) g"
        + " left join databus_agent_conversation_meta m"
        + "   on m.conversation_id = g.conversation_id and m.user_id = g.user_id"
        + " where m.id is null or m.del_flag = '0'"
        + " order by pinned desc, g.last_time desc limit #{limit}")
    List<AgentConversationVo> selectConversations(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * 搜当前用户的会话：命中自定义标题、任一轮问句、任一条消息内容即算命中。
     * <p>
     * 消息内容含 AI 推给前端的文字与画布快照载荷，语义即「这个会话里聊过/动过的东西」；
     * 排序与 {@link #selectConversations} 一致，结果结构直接复用会话摘要。
     *
     * @param userId  发起人
     * @param keyword 关键字（服务层已 trim，非空）
     * @param limit   条数上限
     * @return 命中的会话摘要
     */
    @Select("select g.conversation_id,"
        + " coalesce(m.custom_title,"
        + "   (select left(r2.intent, 200) from databus_agent_run r2"
        + "     where r2.user_id = g.user_id and r2.conversation_id = g.conversation_id"
        + "       and r2.status <> 'SUPERSEDED' and r2.intent is not null and r2.del_flag = '0'"
        + "     order by r2.create_time asc limit 1)) as title,"
        + " g.last_time, g.running, g.run_count,"
        + " (case when m.pinned = '1' then 1 else 0 end) as pinned, m.workspace_id"
        + " from (select user_id, conversation_id, max(create_time) as last_time,"
        + "     max(status = 'RUNNING') as running, count(*) as run_count"
        + "   from databus_agent_run"
        + "   where user_id = #{userId} and conversation_id is not null"
        + "     and status <> 'SUPERSEDED' and del_flag = '0'"
        + "   group by user_id, conversation_id) g"
        + " left join databus_agent_conversation_meta m"
        + "   on m.conversation_id = g.conversation_id and m.user_id = g.user_id"
        + " where (m.id is null or m.del_flag = '0')"
        + "   and (m.custom_title like concat('%', #{keyword}, '%')"
        + "     or exists (select 1 from databus_agent_run r3"
        + "       where r3.user_id = g.user_id and r3.conversation_id = g.conversation_id"
        + "         and r3.del_flag = '0' and r3.intent like concat('%', #{keyword}, '%'))"
        + "     or exists (select 1 from databus_agent_message msg"
        + "       join databus_agent_run r4 on r4.run_id = msg.run_id"
        + "       where r4.user_id = g.user_id and r4.conversation_id = g.conversation_id"
        + "         and r4.del_flag = '0' and msg.content like concat('%', #{keyword}, '%')))"
        + " order by pinned desc, g.last_time desc limit #{limit}")
    List<AgentConversationVo> searchConversations(@Param("userId") Long userId,
                                                  @Param("keyword") String keyword,
                                                  @Param("limit") int limit);

}