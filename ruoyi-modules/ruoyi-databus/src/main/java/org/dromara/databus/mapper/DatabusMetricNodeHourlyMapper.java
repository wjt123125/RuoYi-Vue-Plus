package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusMetricNodeHourly;
import org.dromara.databus.domain.vo.NodeSlowRankVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点指标小时聚合表 Mapper 接口。
 * <p>
 * 小时桶以执行级 start_time 为准（节点明细本身无小时语义），经 execution_id 关联总账取
 * chain_code——节点表未冗余 chain_code，故必须 join。
 *
 * @author databus
 */
public interface DatabusMetricNodeHourlyMapper extends BaseMapperPlus<DatabusMetricNodeHourly, DatabusMetricNodeHourly> {

    /**
     * 按 (chain_code, tag, node_type) 聚合某一小时桶内的节点调用指标。
     * 返回行的 statHour / id 由 Service 补齐后再落库。
     *
     * @param hourStart 小时桶起点（含）
     * @param hourEnd   小时桶终点（不含）
     * @return 节点聚合行（未含 statHour / id / 审计字段）
     */
    @Select("""
        select e.chain_code as chain_code,
               n.tag as tag,
               n.node_type as node_type,
               count(*) as total_count,
               sum(case when n.status = 'SUCCESS' then 1 else 0 end) as success_count,
               sum(case when n.status = 'FAILED' then 1 else 0 end) as failed_count,
               round(avg(n.duration)) as avg_duration,
               max(n.duration) as max_duration
        from databus_execution_node n
        join databus_execution e on n.execution_id = e.id
        where e.start_time >= #{hourStart} and e.start_time < #{hourEnd}
        group by e.chain_code, n.tag, n.node_type
        """)
    List<DatabusMetricNodeHourly> aggregateByHour(@Param("hourStart") LocalDateTime hourStart,
                                                  @Param("hourEnd") LocalDateTime hourEnd);

    /**
     * 窗口内节点耗时排行（平均耗时倒序）。同一节点跨小时/跨链路同名节点分别排行。
     *
     * @param begin 窗口起点（含）
     * @param end   窗口终点（不含）
     * @param limit 条数上限
     * @return 节点耗时排行行
     */
    @Select("""
        select chain_code as chain_code,
               tag as tag,
               node_type as node_type,
               sum(total_count) as total_count,
               sum(failed_count) as failed_count,
               round(sum(avg_duration * total_count) / nullif(sum(total_count), 0)) as avg_duration,
               max(max_duration) as max_duration
        from databus_metric_node_hourly
        where stat_hour >= #{begin} and stat_hour < #{end}
        group by chain_code, tag, node_type
        order by avg_duration desc
        limit #{limit}
        """)
    List<NodeSlowRankVo> selectSlowNodeRank(@Param("begin") LocalDateTime begin,
                                            @Param("end") LocalDateTime end,
                                            @Param("limit") int limit);

}