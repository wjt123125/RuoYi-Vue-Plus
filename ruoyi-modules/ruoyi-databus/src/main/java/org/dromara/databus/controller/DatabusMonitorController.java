package org.dromara.databus.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.web.core.BaseController;
import org.dromara.databus.domain.vo.MetricAggregateVo;
import org.dromara.databus.domain.vo.MonitorErrorStreamVo;
import org.dromara.databus.domain.vo.MonitorOverviewVo;
import org.dromara.databus.domain.vo.MonitorRankingVo;
import org.dromara.databus.domain.vo.MonitorTrendPointVo;
import org.dromara.databus.service.IDatabusMetricService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 数据总线监控大盘接口（阶段5）。
 * <p>
 * 聚合入口：手动触发指标聚合的运维端点（定时任务每小时自动执行）；
 * 查询入口：大盘卡片（含环比）、趋势时间序列、三排行榜、实时错误流——均只读宽表（错误流读原始总账）。
 *
 * @author databus
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/databus/monitor")
public class DatabusMonitorController extends BaseController {

    private final IDatabusMetricService metricService;

    /**
     * 手动触发指标聚合（定时任务每小时自动执行，此端点供运维补算历史小时与验证）。
     *
     * @param hour 目标小时（yyyy-MM-ddTHH:mm:ss 或 yyyy-MM-dd HH:mm:ss，任一小时内的时刻均可），
     *             不传则聚合上一个完整小时
     */
    @SaCheckPermission("databus:monitor:aggregate")
    @Log(title = "数据总线指标聚合", businessType = BusinessType.OTHER)
    @PostMapping("/aggregate")
    public R<MetricAggregateVo> aggregate(@RequestParam(required = false) String hour) {
        return R.ok(metricService.aggregate(parseHour(hour)));
    }

    /**
     * 大盘概览卡片：当前窗口与上一等长窗口的核心指标（前端自算环比）。
     *
     * @param hours 窗口小时数，默认 24、上限 720
     */
    @SaCheckPermission("databus:monitor:query")
    @GetMapping("/overview")
    public R<MonitorOverviewVo> overview(@RequestParam(defaultValue = "24") Integer hours) {
        return R.ok(metricService.overview(hours));
    }

    /**
     * 趋势时间序列：窗口内按小时桶返回的成功/失败次数与成功率、平均耗时。
     *
     * @param hours 窗口小时数，默认 24、上限 720
     */
    @SaCheckPermission("databus:monitor:query")
    @GetMapping("/trend")
    public R<List<MonitorTrendPointVo>> trend(@RequestParam(defaultValue = "24") Integer hours) {
        return R.ok(metricService.trend(hours));
    }

    /**
     * 三张排行榜：链路失败 / 节点耗时 / 错误热点。
     *
     * @param hours 窗口小时数，默认 24、上限 720
     * @param topN  每榜条数，默认 10、上限 50
     */
    @SaCheckPermission("databus:monitor:query")
    @GetMapping("/ranking")
    public R<MonitorRankingVo> ranking(@RequestParam(defaultValue = "24") Integer hours,
                                       @RequestParam(defaultValue = "10") Integer topN) {
        return R.ok(metricService.ranking(hours, topN));
    }

    /**
     * 实时错误流：最近若干条失败执行记录（不限窗口，读原始总账）。
     *
     * @param limit 条数，默认 20、上限 100
     */
    @SaCheckPermission("databus:monitor:query")
    @GetMapping("/error-stream")
    public R<List<MonitorErrorStreamVo>> errorStream(@RequestParam(defaultValue = "20") Integer limit) {
        return R.ok(metricService.errorStream(limit));
    }

    /**
     * 解析目标小时；空白返回 null（走 Service 默认的上一个完整小时）。
     */
    private LocalDateTime parseHour(String hour) {
        if (StringUtils.isBlank(hour)) {
            return null;
        }
        try {
            return LocalDateTime.parse(hour.trim().replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            throw new ServiceException("小时参数格式错误，应为 yyyy-MM-ddTHH:mm:ss");
        }
    }

}