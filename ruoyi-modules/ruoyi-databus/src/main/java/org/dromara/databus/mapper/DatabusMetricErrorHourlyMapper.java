package org.dromara.databus.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.databus.domain.DatabusMetricErrorHourly;
import org.dromara.databus.domain.vo.ErrorHotRankVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 错误指标小时聚合表 Mapper 接口
 *
 * @author databus
 */
public interface DatabusMetricErrorHourlyMapper extends BaseMapperPlus<DatabusMetricErrorHourly, DatabusMetricErrorHourly> {

    /**
     * 窗口内错误热点排行（发生次数倒序）。同一链路下相同指纹跨小时合并计数。
     *
     * @param begin 窗口起点（含）
     * @param end   窗口终点（不含）
     * @param limit 条数上限
     * @return 错误热点排行行
     */
    @Select("""
        select chain_code as chain_code,
               error_fingerprint as error_fingerprint,
               max(error_sample) as error_sample,
               sum(happen_count) as happen_count
        from databus_metric_error_hourly
        where stat_hour >= #{begin} and stat_hour < #{end}
        group by chain_code, error_fingerprint
        order by sum(happen_count) desc
        limit #{limit}
        """)
    List<ErrorHotRankVo> selectHotErrorRank(@Param("begin") LocalDateTime begin,
                                            @Param("end") LocalDateTime end,
                                            @Param("limit") int limit);

}