package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusMetricHourly;
import org.dromara.databus.domain.vo.ChainFailureRankVo;
import org.dromara.databus.domain.vo.MonitorTrendPointVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 链路指标小时聚合宽表 Mapper 接口
 *
 * @author databus
 */
public interface DatabusMetricHourlyMapper extends BaseMapperPlus<DatabusMetricHourly, DatabusMetricHourly> {

    /**
     * 按小时桶汇总趋势点：多链路同一小时的行合并求和，成功率与平均耗时按执行次数加权。
     *
     * @param begin 窗口起点（含）
     * @param end   窗口终点（不含）
     * @return 升序排列的趋势点
     */
    @Select("""
        select stat_hour as stat_hour,
               sum(total_count) as total_count,
               sum(success_count) as success_count,
               sum(failed_count) as failed_count,
               round(sum(success_count) * 100.0 / nullif(sum(total_count), 0), 2) as success_rate,
               round(sum(avg_duration * total_count) / nullif(sum(total_count), 0)) as avg_duration
        from databus_metric_hourly
        where stat_hour >= #{begin} and stat_hour < #{end}
        group by stat_hour
        order by stat_hour
        """)
    List<MonitorTrendPointVo> selectTrend(@Param("begin") LocalDateTime begin,
                                          @Param("end") LocalDateTime end);

    /**
     * 窗口内链路失败排行（失败次数倒序，仅含有失败的链路）。
     *
     * @param begin 窗口起点（含）
     * @param end   窗口终点（不含）
     * @param limit 条数上限
     * @return 失败排行行
     */
    @Select("""
        select chain_code as chain_code,
               sum(total_count) as total_count,
               sum(success_count) as success_count,
               sum(failed_count) as failed_count,
               round(sum(success_count) * 100.0 / nullif(sum(total_count), 0), 2) as success_rate,
               round(sum(avg_duration * total_count) / nullif(sum(total_count), 0)) as avg_duration
        from databus_metric_hourly
        where stat_hour >= #{begin} and stat_hour < #{end}
        group by chain_code
        having sum(failed_count) > 0
        order by sum(failed_count) desc
        limit #{limit}
        """)
    List<ChainFailureRankVo> selectChainFailureRank(@Param("begin") LocalDateTime begin,
                                                    @Param("end") LocalDateTime end,
                                                    @Param("limit") int limit);

}