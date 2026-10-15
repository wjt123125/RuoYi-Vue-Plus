package org.dromara.databus.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.config.properties.DatabusMonitorProperties;
import org.dromara.databus.domain.DatabusAlertRecord;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.DatabusMetricErrorHourly;
import org.dromara.databus.domain.DatabusMetricHourly;
import org.dromara.databus.domain.DatabusMetricNodeHourly;
import org.dromara.databus.domain.vo.ChainHealthVo;
import org.dromara.databus.domain.vo.MetricAggregateVo;
import org.dromara.databus.domain.vo.MonitorErrorStreamVo;
import org.dromara.databus.domain.vo.MonitorOverviewVo;
import org.dromara.databus.domain.vo.MonitorRankingVo;
import org.dromara.databus.domain.vo.MonitorTrendPointVo;
import org.dromara.databus.enums.AlertStatusEnum;
import org.dromara.databus.enums.ExecutionStatusEnum;
import org.dromara.databus.mapper.DatabusAlertRecordMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.mapper.DatabusMetricErrorHourlyMapper;
import org.dromara.databus.mapper.DatabusMetricHourlyMapper;
import org.dromara.databus.mapper.DatabusMetricNodeHourlyMapper;
import org.dromara.databus.service.IDatabusMetricService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 监控指标聚合 Service 实现。
 * <p>
 * 口径：
 * <ul>
 *   <li>小时桶以执行级 {@code databus_execution.start_time} 定界 [statHour, statHour+1h)；</li>
 *   <li>成功率 = 成功数 / 总数 × 100，保留两位；非成功状态（当前实现只有终态）一律计入失败；</li>
 *   <li>耗时分位用最近秩法（nearest-rank）：名次 = ceil(p × N)，样本不足自然落到最大值；</li>
 *   <li>分位不可跨桶/跨链加权合并：窗口与趋势的分位一律取桶内最劣值（见各 VO 注释）；</li>
 *   <li>节点级靠 SQL group by（join 总账取 chain_code），链路级与错误级在 Java 侧分组，
 *       前者需精确算 P95/P99、后者需对错误信息做指纹归一化，都不适合纯 SQL。</li>
 * </ul>
 * 幂等：每张表先按 stat_hour 删旧再批量插，重复执行结果一致。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusMetricServiceImpl implements IDatabusMetricService {

    /**
     * 概览/趋势/排行榜默认窗口小时数。
     */
    private static final int DEFAULT_HOURS = 24;

    /**
     * 窗口小时数上限（30 天，与指标宽表保留期对齐）。
     */
    private static final int MAX_HOURS = 720;

    /**
     * 排行榜默认条数。
     */
    private static final int DEFAULT_TOP_N = 10;

    /**
     * 排行榜条数上限。
     */
    private static final int MAX_TOP_N = 50;

    /**
     * 错误流默认条数。
     */
    private static final int DEFAULT_STREAM_LIMIT = 20;

    /**
     * 错误流条数上限。
     */
    private static final int MAX_STREAM_LIMIT = 100;

    private final DatabusExecutionMapper executionMapper;

    private final DatabusMetricHourlyMapper metricHourlyMapper;

    private final DatabusMetricNodeHourlyMapper metricNodeHourlyMapper;

    private final DatabusMetricErrorHourlyMapper metricErrorHourlyMapper;

    private final DatabusAlertRecordMapper alertRecordMapper;

    private final DatabusMonitorProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MetricAggregateVo aggregate(LocalDateTime hourStart) {
        long begin = System.currentTimeMillis();
        LocalDateTime statHour = (hourStart == null
            ? LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(1)
            : hourStart).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime hourEnd = statHour.plusHours(1);

        // 一次取全该小时执行记录：链路级用 status/duration，错误级复用同一批的 status/errorMsg
        List<DatabusExecution> executions = executionMapper.selectList(
            Wrappers.<DatabusExecution>lambdaQuery()
                .select(DatabusExecution::getId, DatabusExecution::getChainId, DatabusExecution::getChainCode,
                    DatabusExecution::getStatus, DatabusExecution::getErrorMsg, DatabusExecution::getDuration)
                .ge(DatabusExecution::getStartTime, statHour)
                .lt(DatabusExecution::getStartTime, hourEnd));

        int batchSize = properties.getBatchSize();
        MetricAggregateVo vo = new MetricAggregateVo();
        vo.setStatHour(statHour);

        // 链路级：Java 侧分组，精确算 P95/P99
        metricHourlyMapper.delete(Wrappers.<DatabusMetricHourly>lambdaQuery()
            .eq(DatabusMetricHourly::getStatHour, statHour));
        List<DatabusMetricHourly> chainRows = buildChainRows(executions, statHour);
        if (!chainRows.isEmpty()) {
            metricHourlyMapper.insertBatch(chainRows, batchSize);
        }
        vo.setChainRows(chainRows.size());

        // 节点级：SQL group by（join 总账取 chain_code 与小时桶）
        metricNodeHourlyMapper.delete(Wrappers.<DatabusMetricNodeHourly>lambdaQuery()
            .eq(DatabusMetricNodeHourly::getStatHour, statHour));
        List<DatabusMetricNodeHourly> nodeRows = metricNodeHourlyMapper.aggregateByHour(statHour, hourEnd);
        for (DatabusMetricNodeHourly row : nodeRows) {
            row.setId(IdUtil.getSnowflakeNextId());
            row.setStatHour(statHour);
        }
        if (!nodeRows.isEmpty()) {
            metricNodeHourlyMapper.insertBatch(nodeRows, batchSize);
        }
        vo.setNodeRows(nodeRows.size());

        // 错误级：Java 侧按错误指纹归一化后分组计数
        metricErrorHourlyMapper.delete(Wrappers.<DatabusMetricErrorHourly>lambdaQuery()
            .eq(DatabusMetricErrorHourly::getStatHour, statHour));
        List<DatabusMetricErrorHourly> errorRows = buildErrorRows(executions, statHour);
        if (!errorRows.isEmpty()) {
            metricErrorHourlyMapper.insertBatch(errorRows, batchSize);
        }
        vo.setErrorRows(errorRows.size());

        vo.setCostMillis(System.currentTimeMillis() - begin);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int cleanExpired(int retentionDays) {
        if (retentionDays <= 0) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusDays(retentionDays);
        int deleted = metricHourlyMapper.delete(Wrappers.<DatabusMetricHourly>lambdaQuery()
            .lt(DatabusMetricHourly::getStatHour, cutoff));
        deleted += metricNodeHourlyMapper.delete(Wrappers.<DatabusMetricNodeHourly>lambdaQuery()
            .lt(DatabusMetricNodeHourly::getStatHour, cutoff));
        deleted += metricErrorHourlyMapper.delete(Wrappers.<DatabusMetricErrorHourly>lambdaQuery()
            .lt(DatabusMetricErrorHourly::getStatHour, cutoff));
        return deleted;
    }

    @Override
    public MonitorOverviewVo overview(int hours) {
        int span = clampHours(hours);
        // 上界取当前整点 +1h：把「进行中的当前小时桶」纳入展示（手动聚合后立即可对数，
        // 无需等到下个整点）；桶内数据未定时定稿，属实时部分数据，由 hh:05 定时任务幂等覆盖。
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        LocalDateTime begin = end.minusHours(span);

        WindowStat current = collapse(metricHourlyMapper.selectTrend(begin, end));
        WindowStat previous = collapse(metricHourlyMapper.selectTrend(begin.minusHours(span), begin));

        MonitorOverviewVo vo = new MonitorOverviewVo();
        vo.setHours(span);
        vo.setBegin(begin);
        vo.setEnd(end);
        vo.setTotalCount(current.total);
        vo.setSuccessCount(current.success);
        vo.setFailedCount(current.failed);
        vo.setSuccessRate(current.successRate);
        vo.setAvgDuration(current.avgDuration);
        vo.setP95Duration(current.p95);
        vo.setP99Duration(current.p99);
        vo.setActiveChainCount(metricHourlyMapper.countDistinctChains(begin, end));
        vo.setFiringAlertCount(firingAlertCount());
        vo.setPrevTotalCount(previous.total);
        vo.setPrevSuccessCount(previous.success);
        vo.setPrevFailedCount(previous.failed);
        vo.setPrevSuccessRate(previous.successRate);
        vo.setPrevAvgDuration(previous.avgDuration);
        vo.setPrevP95Duration(previous.p95);
        vo.setPrevP99Duration(previous.p99);
        vo.setPrevActiveChainCount(metricHourlyMapper.countDistinctChains(begin.minusHours(span), begin));
        return vo;
    }

    @Override
    public List<MonitorTrendPointVo> trend(int hours) {
        int span = clampHours(hours);
        // 上界 +1h 纳入进行中的当前小时桶（口径同 overview）
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        return metricHourlyMapper.selectTrend(end.minusHours(span), end);
    }

    @Override
    public MonitorRankingVo ranking(int hours, int topN) {
        int span = clampHours(hours);
        int limit = clampTopN(topN);
        // 上界 +1h 纳入进行中的当前小时桶（口径同 overview）
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        LocalDateTime begin = end.minusHours(span);

        MonitorRankingVo vo = new MonitorRankingVo();
        vo.setHours(span);
        vo.setTopN(limit);
        vo.setChainFailures(metricHourlyMapper.selectChainFailureRank(begin, end, limit));
        vo.setSlowNodes(metricNodeHourlyMapper.selectSlowNodeRank(begin, end, limit));
        vo.setHotErrors(metricErrorHourlyMapper.selectHotErrorRank(begin, end, limit));
        return vo;
    }

    @Override
    public List<MonitorErrorStreamVo> errorStream(int limit) {
        int size = clampStreamLimit(limit);
        List<DatabusExecution> executions = executionMapper.selectList(
            Wrappers.<DatabusExecution>lambdaQuery()
                .select(DatabusExecution::getId, DatabusExecution::getChainCode, DatabusExecution::getStatus,
                    DatabusExecution::getErrorMsg, DatabusExecution::getStartTime, DatabusExecution::getDuration)
                .eq(DatabusExecution::getStatus, ExecutionStatusEnum.FAILED.getCode())
                .orderByDesc(DatabusExecution::getStartTime)
                .last("limit " + size));
        List<MonitorErrorStreamVo> rows = new ArrayList<>(executions.size());
        for (DatabusExecution execution : executions) {
            MonitorErrorStreamVo row = new MonitorErrorStreamVo();
            row.setId(execution.getId());
            row.setChainCode(execution.getChainCode());
            row.setStatus(execution.getStatus());
            row.setErrorMsg(execution.getErrorMsg());
            row.setStartTime(execution.getStartTime());
            row.setDuration(execution.getDuration());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<ChainHealthVo> chainHealth(int hours) {
        int span = clampHours(hours);
        // 上界 +1h 纳入进行中的当前小时桶（口径同 overview/trend）
        LocalDateTime end = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).plusHours(1);
        return metricHourlyMapper.selectChainHealth(end.minusHours(span), end);
    }

    /**
     * 当前 FIRING 告警数（时点值）。告警模块异常不影响大盘：兜底返回 0。
     */
    private long firingAlertCount() {
        try {
            Long count = alertRecordMapper.selectCount(
                Wrappers.<DatabusAlertRecord>lambdaQuery()
                    .eq(DatabusAlertRecord::getStatus, AlertStatusEnum.FIRING.getCode()));
            return count == null ? 0L : count;
        } catch (Exception e) {
            log.warn("统计 FIRING 告警数失败，按 0 展示: {}", e.getMessage());
            return 0L;
        }
    }

    /**
     * 把趋势点序列塌缩为一个窗口统计：计数求和，成功率按总量算，平均耗时按执行次数加权，
     * 分位取窗口内最劣值（跨桶不可加权）。
     */
    private WindowStat collapse(List<MonitorTrendPointVo> points) {
        long total = 0;
        long success = 0;
        long failed = 0;
        BigDecimal weightedDuration = BigDecimal.ZERO;
        long durationWeight = 0;
        Long worstP95 = null;
        Long worstP99 = null;
        for (MonitorTrendPointVo point : points) {
            int pointTotal = point.getTotalCount() == null ? 0 : point.getTotalCount();
            total += pointTotal;
            success += point.getSuccessCount() == null ? 0 : point.getSuccessCount();
            failed += point.getFailedCount() == null ? 0 : point.getFailedCount();
            if (point.getAvgDuration() != null && pointTotal > 0) {
                weightedDuration = weightedDuration.add(
                    BigDecimal.valueOf(point.getAvgDuration()).multiply(BigDecimal.valueOf(pointTotal)));
                durationWeight += pointTotal;
            }
            if (point.getP95Duration() != null && (worstP95 == null || point.getP95Duration() > worstP95)) {
                worstP95 = point.getP95Duration();
            }
            if (point.getP99Duration() != null && (worstP99 == null || point.getP99Duration() > worstP99)) {
                worstP99 = point.getP99Duration();
            }
        }
        WindowStat stat = new WindowStat();
        stat.total = (int) total;
        stat.success = (int) success;
        stat.failed = (int) failed;
        stat.successRate = rate((int) success, (int) total);
        stat.avgDuration = durationWeight > 0
            ? weightedDuration.divide(BigDecimal.valueOf(durationWeight), 0, RoundingMode.HALF_UP).longValue()
            : null;
        stat.p95 = worstP95;
        stat.p99 = worstP99;
        return stat;
    }

    /**
     * 窗口小时数兜底：≤0 取默认，超出上限截断。
     */
    private int clampHours(int hours) {
        if (hours <= 0) {
            return DEFAULT_HOURS;
        }
        return Math.min(hours, MAX_HOURS);
    }

    /**
     * 排行榜条数兜底：≤0 取默认，超出上限截断。
     */
    private int clampTopN(int topN) {
        if (topN <= 0) {
            return DEFAULT_TOP_N;
        }
        return Math.min(topN, MAX_TOP_N);
    }

    /**
     * 错误流条数兜底：≤0 取默认，超出上限截断。
     */
    private int clampStreamLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_STREAM_LIMIT;
        }
        return Math.min(limit, MAX_STREAM_LIMIT);
    }

    /**
     * 趋势点序列塌缩后的窗口统计中间体（不入对外 VO）。
     */
    private static class WindowStat {

        private int total;

        private int success;

        private int failed;

        private BigDecimal successRate;

        private Long avgDuration;

        private Long p95;

        private Long p99;

    }

    /**
     * 按链路编码分组，生成链路级小时聚合行。
     */
    private List<DatabusMetricHourly> buildChainRows(List<DatabusExecution> executions, LocalDateTime statHour) {
        Map<String, List<DatabusExecution>> grouped = new LinkedHashMap<>();
        for (DatabusExecution execution : executions) {
            if (StringUtils.isBlank(execution.getChainCode())) {
                continue;
            }
            grouped.computeIfAbsent(execution.getChainCode(), key -> new ArrayList<>()).add(execution);
        }
        String successCode = ExecutionStatusEnum.SUCCESS.getCode();
        List<DatabusMetricHourly> rows = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<DatabusExecution>> entry : grouped.entrySet()) {
            List<DatabusExecution> group = entry.getValue();
            int total = group.size();
            int success = (int) group.stream().filter(e -> successCode.equals(e.getStatus())).count();

            DatabusMetricHourly row = new DatabusMetricHourly();
            row.setId(IdUtil.getSnowflakeNextId());
            row.setChainCode(entry.getKey());
            row.setStatHour(statHour);
            row.setTotalCount(total);
            row.setSuccessCount(success);
            // 非成功状态一律计失败（总账只在流程结束落终态，RUNNING 实际不出现）
            row.setFailedCount(total - success);
            row.setSuccessRate(rate(success, total));
            group.stream().map(DatabusExecution::getChainId).filter(Objects::nonNull).findFirst()
                .ifPresent(row::setChainId);

            List<Long> durations = group.stream().map(DatabusExecution::getDuration)
                .filter(Objects::nonNull).sorted().toList();
            if (!durations.isEmpty()) {
                long sum = durations.stream().mapToLong(Long::longValue).sum();
                row.setAvgDuration(Math.round((double) sum / durations.size()));
                row.setMinDuration(durations.get(0));
                row.setMaxDuration(durations.get(durations.size() - 1));
                row.setP95Duration(percentile(durations, 0.95));
                row.setP99Duration(percentile(durations, 0.99));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 按 (链路编码 + 错误指纹) 分组，生成错误级小时聚合行。
     */
    private List<DatabusMetricErrorHourly> buildErrorRows(List<DatabusExecution> executions, LocalDateTime statHour) {
        String failedCode = ExecutionStatusEnum.FAILED.getCode();
        Map<String, DatabusMetricErrorHourly> grouped = new LinkedHashMap<>();
        for (DatabusExecution execution : executions) {
            if (!failedCode.equals(execution.getStatus())
                || StringUtils.isBlank(execution.getErrorMsg())
                || StringUtils.isBlank(execution.getChainCode())) {
                continue;
            }
            String fingerprint = fingerprint(execution.getErrorMsg());
            String key = execution.getChainCode() + '\u0000' + fingerprint;
            DatabusMetricErrorHourly row = grouped.get(key);
            if (row == null) {
                row = new DatabusMetricErrorHourly();
                row.setId(IdUtil.getSnowflakeNextId());
                row.setChainCode(execution.getChainCode());
                row.setStatHour(statHour);
                row.setErrorFingerprint(fingerprint);
                row.setErrorSample(truncate(execution.getErrorMsg(), 500));
                row.setHappenCount(0);
                grouped.put(key, row);
            }
            row.setHappenCount(row.getHappenCount() + 1);
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 成功率（百分比，保留两位）。总数为 0 时返回 null（无样本，前端展示占位）。
     */
    private BigDecimal rate(int success, int total) {
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(success)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    /**
     * 最近秩法分位：名次 = ceil(p × N)（1 起），样本不足 p × N 时自然落到最大值。
     *
     * @param sortedAsc 升序耗时样本（非空）
     * @param p         分位（如 0.95）
     */
    private long percentile(List<Long> sortedAsc, double p) {
        int n = sortedAsc.size();
        int rank = (int) Math.ceil(p * n);
        if (rank < 1) {
            rank = 1;
        } else if (rank > n) {
            rank = n;
        }
        return sortedAsc.get(rank - 1);
    }

    /**
     * 错误指纹归一化：取首行、抹掉十六进制与数字噪声、压平空白，同类错误归并计数。
     */
    private String fingerprint(String errorMsg) {
        String firstLine = errorMsg.lines().findFirst().orElse("").trim();
        if (firstLine.isEmpty()) {
            firstLine = errorMsg.trim();
        }
        String normalized = firstLine
            .replaceAll("0x[0-9a-fA-F]+", "#")
            .replaceAll("\\d+", "#")
            .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            normalized = firstLine;
        }
        return truncate(normalized, 200);
    }

    /**
     * 截断到指定长度（去首尾空白）。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

}