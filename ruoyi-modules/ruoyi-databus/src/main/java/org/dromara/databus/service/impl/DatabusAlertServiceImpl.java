package org.dromara.databus.service.impl;

import cn.hutool.core.lang.Dict;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.alert.AlertEvent;
import org.dromara.databus.alert.AlertNotifyRouter;
import org.dromara.databus.domain.DatabusAlertRecord;
import org.dromara.databus.domain.DatabusAlertRule;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.bo.DatabusAlertRecordBo;
import org.dromara.databus.domain.bo.DatabusAlertRuleBo;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.domain.vo.DatabusAlertRecordVo;
import org.dromara.databus.domain.vo.DatabusAlertRuleVo;
import org.dromara.databus.enums.AlertNotifyStatusEnum;
import org.dromara.databus.enums.AlertNotifyTypeEnum;
import org.dromara.databus.enums.AlertRuleTypeEnum;
import org.dromara.databus.enums.AlertStatusEnum;
import org.dromara.databus.enums.ExecutionStatusEnum;
import org.dromara.databus.enums.TriggerSourceEnum;
import org.dromara.databus.mapper.DatabusAlertRecordMapper;
import org.dromara.databus.mapper.DatabusAlertRuleMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.service.IDatabusAlertService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据总线告警引擎 Service 实现。
 * <p>
 * 记录状态机「一次告警生命周期一行」：评估任务对每条启用规则取最近一条记录判定当前状态——
 * 由「正常/已恢复」进入命中即插入一条 FIRING 记录，由 FIRING 恢复即把该行翻成
 * RECOVERED 并回填恢复时间；持续触发/持续正常期间不动，天然避免每分钟重复通知。
 * 触发与恢复都发通知，接收方按事件状态区分。
 * <p>
 * 通知分发走 {@link AlertNotifyRouter}（策略式多渠道：EMAIL/WEBHOOK/CHAIN），引擎只管
 * 「什么时候该通知」；逐渠道成败写进记录的 notify_detail 便于排查「命中了但没收到通知」。
 * <p>
 * 判定口径（窗口 = 现在往前 window_minutes 分钟，按执行级 databus_execution.start_time 定界）：
 * <ul>
 *   <li>CONSECUTIVE_FAILURE：窗口内最近 threshold_count 次执行全部为 FAILED；</li>
 *   <li>SUCCESS_RATE：窗口内成功率（成功/总数×100，两位）低于 threshold_rate。</li>
 * </ul>
 * 评估查询统一排除 trigger_source='ALERT'：告警通知链自己也落执行总账，若参与评估，
 * 「通知链失败→再告警→再通知」就是死循环。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusAlertServiceImpl implements IDatabusAlertService {

    /**
     * 启用标记。
     */
    private static final String ENABLED_YES = "Y";

    /**
     * 窗口分钟缺省值（表列默认 5，此处兜底异常数据）。
     */
    private static final int DEFAULT_WINDOW_MINUTES = 5;

    /**
     * 规则级评估锁（规则数有限，Map 不清理）；防事件评估与定时评估并发重复通知。
     */
    private final Map<Long, Object> RULE_LOCKS = new ConcurrentHashMap<>();

    private final DatabusAlertRuleMapper ruleMapper;

    private final DatabusAlertRecordMapper recordMapper;

    private final DatabusExecutionMapper executionMapper;

    private final AlertNotifyRouter notifyRouter;

    @Override
    public PageResult<DatabusAlertRuleVo> queryRulePageList(DatabusAlertRuleBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusAlertRule> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getRuleName()), DatabusAlertRule::getRuleName, bo.getRuleName());
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusAlertRule::getChainCode, bo.getChainCode());
        lqw.eq(StringUtils.isNotBlank(bo.getRuleType()), DatabusAlertRule::getRuleType, bo.getRuleType());
        lqw.eq(StringUtils.isNotBlank(bo.getEnabled()), DatabusAlertRule::getEnabled, bo.getEnabled());
        lqw.orderByDesc(DatabusAlertRule::getUpdateTime)
            .orderByDesc(DatabusAlertRule::getId);
        Page<DatabusAlertRuleVo> result = ruleMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public DatabusAlertRuleVo queryRuleById(Long id) {
        return ruleMapper.selectVoById(id);
    }

    @Override
    public Boolean insertRule(DatabusAlertRuleBo bo) {
        validateRuleType(bo);
        validateNotifyChannels(bo);
        DatabusAlertRule add = MapstructUtils.convert(bo, DatabusAlertRule.class);
        add.setId(null);
        add.setEnabled(StringUtils.isBlank(bo.getEnabled()) ? ENABLED_YES : bo.getEnabled());
        normalizeThreshold(add);
        return ruleMapper.insert(add) > 0;
    }

    @Override
    public Boolean updateRule(DatabusAlertRuleBo bo) {
        if (ruleMapper.selectById(bo.getId()) == null) {
            throw new ServiceException("告警规则不存在或已删除");
        }
        validateRuleType(bo);
        validateNotifyChannels(bo);
        DatabusAlertRule update = MapstructUtils.convert(bo, DatabusAlertRule.class);
        update.setEnabled(StringUtils.isBlank(bo.getEnabled()) ? ENABLED_YES : bo.getEnabled());
        normalizeThreshold(update);
        return ruleMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteRuleByIds(Collection<Long> ids) {
        return ruleMapper.deleteByIds(ids) > 0;
    }

    @Override
    public PageResult<DatabusAlertRecordVo> queryRecordPageList(DatabusAlertRecordBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusAlertRecord> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getRuleName()), DatabusAlertRecord::getRuleName, bo.getRuleName());
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusAlertRecord::getChainCode, bo.getChainCode());
        lqw.eq(StringUtils.isNotBlank(bo.getAlertType()), DatabusAlertRecord::getAlertType, bo.getAlertType());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), DatabusAlertRecord::getStatus, bo.getStatus());
        lqw.between(StringUtils.isNotBlank(bo.getBeginTime()) && StringUtils.isNotBlank(bo.getEndTime()),
            DatabusAlertRecord::getTriggerTime, bo.getBeginTime(), bo.getEndTime());
        lqw.orderByDesc(DatabusAlertRecord::getTriggerTime)
            .orderByDesc(DatabusAlertRecord::getId);
        Page<DatabusAlertRecordVo> result = recordMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public AlertEvaluateVo evaluate() {
        List<DatabusAlertRule> rules = ruleMapper.selectList(
            Wrappers.<DatabusAlertRule>lambdaQuery()
                .eq(DatabusAlertRule::getEnabled, ENABLED_YES));
        return evaluateRules(rules);
    }

    @Override
    public AlertEvaluateVo evaluateChain(String chainCode) {
        if (StringUtils.isBlank(chainCode)) {
            return new AlertEvaluateVo();
        }
        List<DatabusAlertRule> rules = ruleMapper.selectList(
            Wrappers.<DatabusAlertRule>lambdaQuery()
                .eq(DatabusAlertRule::getEnabled, ENABLED_YES)
                .eq(DatabusAlertRule::getChainCode, chainCode));
        return evaluateRules(rules);
    }

    /**
     * 评估一组规则并汇总（全量扫描与单链路事件触发共用）。
     * 按规则加 JVM 锁串行：事件评估（执行线程触发）与分钟级定时评估可能并发，
     * 无锁时「同查 latest、同判未 FIRING、双双插入」会重复通知；单机部署 JVM 锁足够，
     * 多节点阶段再换数据库乐观锁/唯一约束。
     */
    private AlertEvaluateVo evaluateRules(List<DatabusAlertRule> rules) {
        long begin = System.currentTimeMillis();
        AlertEvaluateVo vo = new AlertEvaluateVo();
        vo.setRuleCount(rules.size());
        for (DatabusAlertRule rule : rules) {
            // 单条规则异常（脏数据/渠道调用超时等）不阻断其余规则
            try {
                synchronized (RULE_LOCKS.computeIfAbsent(rule.getId(), k -> new Object())) {
                    evaluateRule(rule, vo);
                }
            } catch (Exception e) {
                log.error("告警规则评估失败 ruleId={} ruleName={} err={}", rule.getId(), rule.getRuleName(), e.getMessage());
            }
        }
        vo.setCostMillis(System.currentTimeMillis() - begin);
        return vo;
    }

    /**
     * 评估单条规则：命中则触发（或维持），恢复则翻转。
     *
     * @param rule 启用规则
     * @param vo   汇总累加器
     */
    private void evaluateRule(DatabusAlertRule rule, AlertEvaluateVo vo) {
        DatabusAlertRecord latest = latestRecord(rule.getId());
        boolean firing = latest != null && AlertStatusEnum.FIRING.getCode().equals(latest.getStatus());
        String hitMessage = match(rule);

        if (hitMessage != null && !firing) {
            // 由 正常/已恢复 进入命中：插入 FIRING 记录并多渠道通知
            fire(rule, hitMessage, vo);
        } else if (hitMessage == null && firing) {
            // 由 FIRING 恢复：翻转最近一条记录并补发恢复通知
            recover(rule, latest, vo);
        }
    }

    /**
     * 触发一条告警：插入 FIRING 记录，多渠道分发通知后回填通知结果快照。
     */
    private void fire(DatabusAlertRule rule, String hitMessage, AlertEvaluateVo vo) {
        LocalDateTime triggerTime = LocalDateTime.now();
        DatabusAlertRecord record = new DatabusAlertRecord();
        record.setRuleId(rule.getId());
        record.setRuleName(rule.getRuleName());
        record.setChainCode(rule.getChainCode());
        record.setAlertType(rule.getRuleType());
        record.setStatus(AlertStatusEnum.FIRING.getCode());
        record.setMessage(hitMessage);
        record.setNotifyType(rule.getNotifyType());
        record.setTriggerTime(triggerTime);
        recordMapper.insert(record);

        // 通知拿事件说话（含 recordId，通知链可据此回查记录）；先插后通知才有主键可带
        AlertEvent event = AlertEvent.of(rule, record, AlertStatusEnum.FIRING.getCode(), hitMessage);
        applyNotifyResult(record, notifyRouter.notify(rule, event));

        vo.setFiredCount(vo.getFiredCount() + 1);
        countNotify(vo, record.getNotifyStatus());
        log.warn("告警触发：ruleId={} ruleName={} chainCode={} type={} 渠道={} notifyStatus={} 说明={}",
            rule.getId(), rule.getRuleName(), rule.getChainCode(), rule.getRuleType(),
            rule.getNotifyType(), record.getNotifyStatus(), hitMessage);
    }

    /**
     * 恢复一条告警：翻转 FIRING 记录为 RECOVERED，多渠道补发恢复通知并回填通知结果。
     */
    private void recover(DatabusAlertRule rule, DatabusAlertRecord latest, AlertEvaluateVo vo) {
        LocalDateTime recoverTime = LocalDateTime.now();
        latest.setStatus(AlertStatusEnum.RECOVERED.getCode());
        latest.setRecoverTime(recoverTime);
        latest.setNotifyType(rule.getNotifyType());

        AlertEvent event = AlertEvent.of(rule, latest, AlertStatusEnum.RECOVERED.getCode(),
            "窗口评估恢复正常");
        applyNotifyResult(latest, notifyRouter.notify(rule, event));
        recordMapper.updateById(latest);

        vo.setRecoveredCount(vo.getRecoveredCount() + 1);
        countNotify(vo, latest.getNotifyStatus());
        log.info("告警恢复：ruleId={} ruleName={} chainCode={} 原触发时间={} notifyStatus={}",
            rule.getId(), rule.getRuleName(), rule.getChainCode(), latest.getTriggerTime(), latest.getNotifyStatus());
    }

    /**
     * 把路由结果写回记录（总状态 + 逐渠道明细）。
     */
    private void applyNotifyResult(DatabusAlertRecord record, AlertNotifyRouter.NotifyResult result) {
        record.setNotifyStatus(result.status());
        record.setNotifyDetail(result.detail());
    }

    /**
     * 按总状态累加评估汇总计数（DISPATCHED/PARTIAL 不单列，只统计两头的确定结果）。
     */
    private void countNotify(AlertEvaluateVo vo, String status) {
        if (AlertNotifyStatusEnum.SUCCESS.getCode().equals(status)) {
            vo.setNotifySuccess(vo.getNotifySuccess() + 1);
        } else if (AlertNotifyStatusEnum.FAILED.getCode().equals(status)) {
            vo.setNotifyFailed(vo.getNotifyFailed() + 1);
        }
    }

    /**
     * 取该规则最近一条告警记录（判断当前是否处于 FIRING）。
     */
    private DatabusAlertRecord latestRecord(Long ruleId) {
        return recordMapper.selectOne(
            Wrappers.<DatabusAlertRecord>lambdaQuery()
                .eq(DatabusAlertRecord::getRuleId, ruleId)
                .orderByDesc(DatabusAlertRecord::getId)
                .last("limit 1"));
    }

    /**
     * 判定规则是否命中，返回命中说明；未命中返回 null。
     */
    private String match(DatabusAlertRule rule) {
        String type = rule.getRuleType();
        if (AlertRuleTypeEnum.CONSECUTIVE_FAILURE.getCode().equals(type)) {
            return matchConsecutiveFailure(rule);
        }
        if (AlertRuleTypeEnum.SUCCESS_RATE.getCode().equals(type)) {
            return matchSuccessRate(rule);
        }
        log.warn("未知告警规则类型，跳过评估 ruleId={} type={}", rule.getId(), type);
        return null;
    }

    /**
     * 连续失败判定：窗口内最近 threshold_count 次执行全部为 FAILED。
     * 排除 trigger_source='ALERT'（通知链自己的执行，防死循环）。
     */
    private String matchConsecutiveFailure(DatabusAlertRule rule) {
        int n = rule.getThresholdCount() == null ? 0 : rule.getThresholdCount();
        if (n <= 0) {
            return null;
        }
        LocalDateTime windowBegin = LocalDateTime.now().minusMinutes(windowMinutes(rule));
        // threshold_count 为整型（Service 层已校验 > 0），limit 拼接不存在注入面
        List<DatabusExecution> recent = executionMapper.selectList(
            Wrappers.<DatabusExecution>lambdaQuery()
                .select(DatabusExecution::getId, DatabusExecution::getStatus)
                .eq(DatabusExecution::getChainCode, rule.getChainCode())
                .ge(DatabusExecution::getStartTime, windowBegin)
                .ne(DatabusExecution::getTriggerSource, TriggerSourceEnum.ALERT.getCode())
                .orderByDesc(DatabusExecution::getStartTime)
                .orderByDesc(DatabusExecution::getId)
                .last("limit " + n));
        if (recent.size() < n) {
            return null;
        }
        String failedCode = ExecutionStatusEnum.FAILED.getCode();
        boolean allFailed = recent.stream().allMatch(e -> failedCode.equals(e.getStatus()));
        return allFailed ? "窗口内最近 " + n + " 次执行全部失败" : null;
    }

    /**
     * 成功率判定：窗口内成功率（成功/总数×100，两位）低于 threshold_rate。
     * 排除 trigger_source='ALERT'（通知链自己的执行，防死循环）。
     */
    private String matchSuccessRate(DatabusAlertRule rule) {
        BigDecimal threshold = rule.getThresholdRate();
        if (threshold == null) {
            return null;
        }
        LocalDateTime windowBegin = LocalDateTime.now().minusMinutes(windowMinutes(rule));
        Long totalBox = executionMapper.selectCount(
            Wrappers.<DatabusExecution>lambdaQuery()
                .eq(DatabusExecution::getChainCode, rule.getChainCode())
                .ge(DatabusExecution::getStartTime, windowBegin)
                .ne(DatabusExecution::getTriggerSource, TriggerSourceEnum.ALERT.getCode()));
        long total = totalBox == null ? 0L : totalBox;
        if (total <= 0) {
            return null;
        }
        Long failedBox = executionMapper.selectCount(
            Wrappers.<DatabusExecution>lambdaQuery()
                .eq(DatabusExecution::getChainCode, rule.getChainCode())
                .ge(DatabusExecution::getStartTime, windowBegin)
                .ne(DatabusExecution::getTriggerSource, TriggerSourceEnum.ALERT.getCode())
                .eq(DatabusExecution::getStatus, ExecutionStatusEnum.FAILED.getCode()));
        long failed = failedBox == null ? 0L : failedBox;
        BigDecimal rate = BigDecimal.valueOf(total - failed)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        if (rate.compareTo(threshold) >= 0) {
            return null;
        }
        return "窗口内成功率 " + rate.toPlainString() + "% 低于阈值 " + threshold.toPlainString()
            + "%（共 " + total + " 次，失败 " + failed + " 次）";
    }

    /**
     * 窗口分钟兜底（null/≤0 取默认）。
     */
    private int windowMinutes(DatabusAlertRule rule) {
        Integer minutes = rule.getWindowMinutes();
        return minutes == null || minutes <= 0 ? DEFAULT_WINDOW_MINUTES : minutes;
    }

    /**
     * 规则类型与专属阈值校验（类型未知或阈值缺失直接拦截，给出带语义的报错）。
     */
    private void validateRuleType(DatabusAlertRuleBo bo) {
        if (AlertRuleTypeEnum.CONSECUTIVE_FAILURE.getCode().equals(bo.getRuleType())) {
            if (bo.getThresholdCount() == null || bo.getThresholdCount() <= 0) {
                throw new ServiceException("连续失败规则需填写大于 0 的次数阈值");
            }
        } else if (AlertRuleTypeEnum.SUCCESS_RATE.getCode().equals(bo.getRuleType())) {
            BigDecimal rate = bo.getThresholdRate();
            if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new ServiceException("成功率规则需填写 0-100 之间的成功率下限");
            }
        } else {
            throw new ServiceException("不支持的告警规则类型: " + bo.getRuleType());
        }
    }

    /**
     * 通知渠道校验：渠道类型必须已支持且不重复；notify_config 必须是合法 JSON 对象，
     * 且每个勾选渠道都有自己必需的参数（EMAIL 要 emails、WEBHOOK 要 url、CHAIN 要 chainCode）。
     */
    private void validateNotifyChannels(DatabusAlertRuleBo bo) {
        List<String> types = Arrays.stream(StringUtils.defaultString(bo.getNotifyType()).split(","))
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .toList();
        if (types.isEmpty()) {
            throw new ServiceException("至少选择一个通知渠道");
        }
        for (String type : types) {
            if (!AlertNotifyTypeEnum.isValid(type)) {
                throw new ServiceException("不支持的通知渠道: " + type);
            }
        }
        if (types.stream().distinct().count() != types.size()) {
            throw new ServiceException("通知渠道重复勾选: " + bo.getNotifyType());
        }
        Dict configRoot = parseNotifyConfigRoot(bo.getNotifyConfig());
        for (String type : types) {
            Object channelNode = configRoot == null ? null : configRoot.get(type);
            if (!(channelNode instanceof Map<?, ?> channelConfig) || channelConfig.isEmpty()) {
                throw new ServiceException("渠道 " + AlertNotifyTypeEnum.infoOf(type) + " 缺少配置参数");
            }
            String requiredKey = requiredConfigKey(type);
            Object requiredValue = channelConfig.get(requiredKey);
            if (requiredValue == null || StringUtils.isBlank(String.valueOf(requiredValue))) {
                throw new ServiceException("渠道 " + AlertNotifyTypeEnum.infoOf(type)
                    + " 缺少 " + requiredKey + " 参数");
            }
        }
    }

    /**
     * 解析 notify_config 根对象；为空允许（后面按渠道缺配置拦截），非对象格式直接拒绝。
     */
    private Dict parseNotifyConfigRoot(String notifyConfig) {
        if (StringUtils.isBlank(notifyConfig)) {
            return null;
        }
        Dict root = JsonUtils.parseMap(notifyConfig);
        if (root == null) {
            throw new ServiceException("渠道配置必须是合法的 JSON 对象");
        }
        return root;
    }

    /**
     * 各渠道的必需参数键（与对应 AlertNotifier 实现的解析口径一致）。
     */
    private String requiredConfigKey(String type) {
        return switch (type) {
            case "EMAIL" -> "emails";
            case "WEBHOOK" -> "url";
            case "CHAIN" -> "chainCode";
            default -> "";
        };
    }

    /**
     * 清掉与当前类型无关的阈值字段，避免编辑切换类型时残留旧值。
     */
    private void normalizeThreshold(DatabusAlertRule rule) {
        if (AlertRuleTypeEnum.CONSECUTIVE_FAILURE.getCode().equals(rule.getRuleType())) {
            rule.setThresholdRate(null);
        } else if (AlertRuleTypeEnum.SUCCESS_RATE.getCode().equals(rule.getRuleType())) {
            rule.setThresholdCount(null);
        }
    }

}
