package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusAlertRule;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 数据总线告警规则视图对象 databus_alert_rule
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusAlertRule.class)
public class DatabusAlertRuleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id
     */
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
     * 规则类型
     */
    private String ruleType;

    /**
     * 评估窗口（分钟）
     */
    private Integer windowMinutes;

    /**
     * 连续失败次数阈值
     */
    private Integer thresholdCount;

    /**
     * 成功率下限百分比
     */
    private BigDecimal thresholdRate;

    /**
     * 通知渠道（EMAIL/WEBHOOK/CHAIN 逗号分隔）
     */
    private String notifyType;

    /**
     * 各渠道参数 JSON
     */
    private String notifyConfig;

    /**
     * 是否启用（Y/N）
     */
    private String enabled;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}