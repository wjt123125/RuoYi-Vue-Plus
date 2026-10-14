package org.dromara.databus.domain.vo;

import lombok.Data;

import java.util.List;

/**
 * 监控大盘三张排行榜聚合返回体。
 *
 * @author databus
 */
@Data
public class MonitorRankingVo {

    /**
     * 统计窗口小时数。
     */
    private Integer hours;

    /**
     * 排行榜条数上限。
     */
    private Integer topN;

    /**
     * 链路失败排行（失败次数倒序）。
     */
    private List<ChainFailureRankVo> chainFailures;

    /**
     * 节点耗时排行（平均耗时倒序）。
     */
    private List<NodeSlowRankVo> slowNodes;

    /**
     * 错误热点排行（发生次数倒序）。
     */
    private List<ErrorHotRankVo> hotErrors;

}