package org.dromara.databus.service;

import org.dromara.databus.domain.vo.MetricAggregateVo;
import org.dromara.databus.domain.vo.MonitorErrorStreamVo;
import org.dromara.databus.domain.vo.MonitorOverviewVo;
import org.dromara.databus.domain.vo.MonitorRankingVo;
import org.dromara.databus.domain.vo.MonitorTrendPointVo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据总线监控指标聚合 Service（阶段5 监控体系）。
 * <p>
 * 把某一完整小时内的执行记录预聚合进三张宽表，供大盘/趋势/排行榜只读消费。
 * 聚合对目标小时先删后插，重复执行结果一致（幂等）；定时任务与手动端点共用本入口。
 *
 * @author databus
 */
public interface IDatabusMetricService {

    /**
     * 聚合指定小时桶的链路级/节点级/错误级指标。
     *
     * @param hourStart 目标小时桶（任意处于该小时内的时刻，落库时按整点截断）；
     *                  null 时取「上一个完整小时」——定时任务每小时调用即用此默认
     * @return 聚合结果（各表写入行数与耗时）
     */
    MetricAggregateVo aggregate(LocalDateTime hourStart);

    /**
     * 清理超过保留期的指标宽表行（三表 stat_hour 早于 now - 保留天数 的成品）。
     *
     * @param retentionDays 保留天数，≤0 时不清理并返回 0
     * @return 删除总行数（三表合计）
     */
    int cleanExpired(int retentionDays);

    /**
     * 大盘概览卡片：当前窗口与上一等长窗口的核心指标（供前端算环比）。
     *
     * @param hours 窗口小时数（≤0 或过大时内部按默认/上限处理，默认 24、上限 720）
     * @return 概览（含两窗口口径）
     */
    MonitorOverviewVo overview(int hours);

    /**
     * 趋势时间序列：窗口内按小时桶返回的点序列（缺数据的小时无点，前端按桶补零）。
     *
     * @param hours 窗口小时数
     * @return 升序趋势点
     */
    List<MonitorTrendPointVo> trend(int hours);

    /**
     * 三张排行榜：链路失败 / 节点耗时 / 错误热点。
     *
     * @param hours 窗口小时数
     * @param topN  每榜条数上限（≤0 或过大时按默认 10、上限 50 处理）
     * @return 排行榜聚合体
     */
    MonitorRankingVo ranking(int hours, int topN);

    /**
     * 实时错误流：不限窗口，取最近若干条失败执行记录（读原始总账，无需等整点聚合）。
     *
     * @param limit 条数上限（≤0 或过大时按默认 20、上限 100 处理）
     * @return 错误流条目（按开始时间倒序）
     */
    List<MonitorErrorStreamVo> errorStream(int limit);

}