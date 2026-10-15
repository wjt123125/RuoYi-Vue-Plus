package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;

/**
 * 数据总线告警规则对象 databus_alert_rule。
 * <p>
 * 一条规则 = 「目标链路 + 规则类型 + 时间窗口」的评估条件 + 通知渠道配置。评估由
 * {@code DatabusAlertEvaluateJob} 每分钟扫描启用规则执行；窗口内命中即触发一条
 * {@link DatabusAlertRecord}（FIRING）并按 notifyType 分发通知，恢复时翻转该记录。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_alert_rule")
public class DatabusAlertRule extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 规则名称
     */
    private String ruleName;

    /**
     * 目标链路编码
     */
    private String chainCode;

    /**
     * 规则类型（CONSECUTIVE_FAILURE / SUCCESS_RATE，见 AlertRuleTypeEnum）
     */
    private String ruleType;

    /**
     * 评估窗口（分钟）
     */
    private Integer windowMinutes;

    /**
     * 连续失败次数阈值（CONSECUTIVE_FAILURE 用；其余类型为空）
     */
    private Integer thresholdCount;

    /**
     * 成功率下限百分比（SUCCESS_RATE 用，0-100；其余类型为空）
     */
    private BigDecimal thresholdRate;

    /**
     * 通知渠道（EMAIL/WEBHOOK/CHAIN，多选逗号分隔，见 AlertNotifyTypeEnum）
     */
    private String notifyType;

    /**
     * 各渠道参数 JSON（键为渠道类型，如 {"EMAIL":{"emails":"a@x.com"},"CHAIN":{"chainCode":"x"}}）
     */
    private String notifyConfig;

    /**
     * 是否启用（Y启用 N停用）
     */
    private String enabled;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）
     */
    private String remark;

}