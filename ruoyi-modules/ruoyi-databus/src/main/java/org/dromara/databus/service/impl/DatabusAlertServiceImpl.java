package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mail.core.MailBuilder;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.DatabusAlertRecord;
import org.dromara.databus.domain.DatabusAlertRule;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.bo.DatabusAlertRecordBo;
import org.dromara.databus.domain.bo.DatabusAlertRuleBo;
import org.dromara.databus.domain.vo.AlertEvaluateVo;
import org.dromara.databus.domain.vo.DatabusAlertRecordVo;
import org.dromara.databus.domain.vo.DatabusAlertRuleVo;
import org.dromara.databus.enums.AlertNotifyStatusEnum;
import org.dromara.databus.enums.AlertRuleTypeEnum;
import org.dromara.databus.enums.AlertStatusEnum;
import org.dromara.databus.enums.ExecutionStatusEnum;
import org.dromara.databus.mapper.DatabusAlertRecordMapper;
import org.dromara.databus.mapper.DatabusAlertRuleMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.service.IDatabusAlertService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;

/**
 * 数据总线告警引擎 Service 实现。
 * <p>
 * 记录状态机「一次告警生命周期一行」：评估任务对每条启用规则取最近一条记录判定当前状态——
 * 由「正常/已恢复」进入命中即插入一条 FIRING 记录（同时发邮件），由 FIRING 恢复即把该行翻成
 * RECOVERED 并回填恢复时间；持续触发/持续正常期间不动，天然避免每分钟重复通知。
 * <p>
 * 判定口径（窗口 = 现在往前 window_minutes 分钟，按执行级 databus_execution.start_time 定界）：
 * <ul>
 *   <li>CONSECUTIVE_FAILURE：窗口内最近 threshold_count 次执行全部为 FAILED；</li>
 *   <li>SUCCESS_RATE：窗口内成功率（成功/总数×100，两位）低于 threshold_rate。</li>
 * </ul>
 * 邮件通道未启用（mail.enabled=false）或 SMTP 异常都落到 notify_status=FAILED 留痕，不阻断评估。
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
     * 邮件时间格式。
     */
    private static final DateTimeFormatter MAIL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final DatabusAlertRuleMapper ruleMapper;

    private final DatabusAlertRecordMapper recordMapper;

    private final DatabusExecutionMapper executionMapper;

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
        long begin = System.currentTimeMillis();
        List<DatabusAlertRule> rules = ruleMapper.selectList(
            Wrappers.<DatabusAlertRule>lambdaQuery()
                .eq(DatabusAlertRule::getEnabled, ENABLED_YES));
        AlertEvaluateVo vo = new AlertEvaluateVo();
        vo.setRuleCount(rules.size());
        for (DatabusAlertRule rule : rules) {
            // 单条规则异常（脏数据/邮件超时等）不阻断其余规则
            try {
                evaluateRule(rule, vo);
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
            // 由 正常/已恢复 进入命中：插入 FIRING 记录并通知
            fire(rule, hitMessage, vo);
        } else if (hitMessage == null && firing) {
            // 由 FIRING 恢复：翻转最近一条记录
            LocalDateTime recoverTime = LocalDateTime.now();
            DatabusAlertRecord recover = new DatabusAlertRecord();
            recover.setId(latest.getId());
            recover.setStatus(AlertStatusEnum.RECOVERED.getCode());
            recover.setRecoverTime(recoverTime);
            recordMapper.updateById(recover);
            vo.setRecoveredCount(vo.getRecoveredCount() + 1);
            log.info("告警恢复：ruleId={} ruleName={} chainCode={} 原触发时间={}",
                rule.getId(), rule.getRuleName(), rule.getChainCode(), latest.getTriggerTime());
        }
    }

    /**
     * 触发一条告警：插入 FIRING 记录（含通知结果快照）并累加计数。
     */
    private void fire(DatabusAlertRule rule, String hitMessage, AlertEvaluateVo vo) {
        LocalDateTime triggerTime = LocalDateTime.now();
        String notifyStatus = notify(rule, hitMessage, triggerTime);

        DatabusAlertRecord record = new DatabusAlertRecord();
        record.setRuleId(rule.getId());
        record.setRuleName(rule.getRuleName());
        record.setChainCode(rule.getChainCode());
        record.setAlertType(rule.getRuleType());
        record.setStatus(AlertStatusEnum.FIRING.getCode());
        record.setMessage(hitMessage);
        record.setNotifyEmails(rule.getNotifyEmails());
        record.setNotifyStatus(notifyStatus);
        record.setTriggerTime(triggerTime);
        recordMapper.insert(record);

        vo.setFiredCount(vo.getFiredCount() + 1);
        if (AlertNotifyStatusEnum.SUCCESS.getCode().equals(notifyStatus)) {
            vo.setNotifySuccess(vo.getNotifySuccess() + 1);
        } else if (AlertNotifyStatusEnum.FAILED.getCode().equals(notifyStatus)) {
            vo.setNotifyFailed(vo.getNotifyFailed() + 1);
        }
        log.warn("告警触发：ruleId={} ruleName={} chainCode={} type={} notifyStatus={} 说明={}",
            rule.getId(), rule.getRuleName(), rule.getChainCode(), rule.getRuleType(), notifyStatus, hitMessage);
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
                .ge(DatabusExecution::getStartTime, windowBegin));
        long total = totalBox == null ? 0L : totalBox;
        if (total <= 0) {
            return null;
        }
        Long failedBox = executionMapper.selectCount(
            Wrappers.<DatabusExecution>lambdaQuery()
                .eq(DatabusExecution::getChainCode, rule.getChainCode())
                .ge(DatabusExecution::getStartTime, windowBegin)
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
     * 发送告警邮件。收件人空白 → SKIPPED；通道未启用或 SMTP 异常 → FAILED（留痕不抛出）。
     */
    private String notify(DatabusAlertRule rule, String hitMessage, LocalDateTime triggerTime) {
        if (StringUtils.isBlank(rule.getNotifyEmails())) {
            return AlertNotifyStatusEnum.SKIPPED.getCode();
        }
        try {
            MailBuilder.of()
                .to(rule.getNotifyEmails())
                .subject("【数据总线告警】" + rule.getRuleName())
                .text(buildMailText(rule, hitMessage, triggerTime))
                .send();
            return AlertNotifyStatusEnum.SUCCESS.getCode();
        } catch (Exception e) {
            log.warn("告警邮件发送失败（邮件通道未启用或 SMTP 异常）ruleId={} ruleName={} err={}",
                rule.getId(), rule.getRuleName(), e.getMessage());
            return AlertNotifyStatusEnum.FAILED.getCode();
        }
    }

    /**
     * 组装纯文本告警邮件正文。
     */
    private String buildMailText(DatabusAlertRule rule, String hitMessage, LocalDateTime triggerTime) {
        return "数据总线链路告警\n"
            + "规则名称：" + rule.getRuleName() + "\n"
            + "目标链路：" + rule.getChainCode() + "\n"
            + "规则类型：" + typeInfo(rule.getRuleType()) + "\n"
            + "评估窗口：最近 " + windowMinutes(rule) + " 分钟\n"
            + "命中条件：" + hitMessage + "\n"
            + "触发时间：" + triggerTime.format(MAIL_TIME_FORMATTER) + "\n"
            + "\n请及时排查数据总线链路执行情况。\n";
    }

    /**
     * 规则类型说明（枚举兜底回退原始编码）。
     */
    private String typeInfo(String ruleType) {
        for (AlertRuleTypeEnum type : AlertRuleTypeEnum.values()) {
            if (type.getCode().equals(ruleType)) {
                return type.getInfo();
            }
        }
        return ruleType;
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