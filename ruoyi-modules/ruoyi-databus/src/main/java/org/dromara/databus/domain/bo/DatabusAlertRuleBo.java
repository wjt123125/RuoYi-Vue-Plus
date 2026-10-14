package org.dromara.databus.domain.bo;

import io.github.linpeilie.annotations.AutoMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.dromara.databus.domain.DatabusAlertRule;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 数据总线告警规则业务对象 databus_alert_rule。
 * <p>
 * 列表查询（ruleName / chainCode / ruleType / enabled）与新增修改共用本对象；
 * ruleType 专属阈值（thresholdCount / thresholdRate）的「按类型必填」在 Service 层校验，
 * 以便给出「连续失败需填次数阈值」这类带语义的报错。
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusAlertRule.class, reverseConvertGenerate = false)
public class DatabusAlertRuleBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id（新增为空，编辑必填）
     */
    @NotNull(message = "主键不能为空", groups = {EditGroup.class})
    private Long id;

    /**
     * 规则名称（模糊匹配用）
     */
    @NotBlank(message = "规则名称不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "规则名称长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String ruleName;

    /**
     * 目标链路编码
     */
    @NotBlank(message = "目标链路编码不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 100, message = "目标链路编码长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String chainCode;

    /**
     * 规则类型（CONSECUTIVE_FAILURE / SUCCESS_RATE）
     */
    @NotBlank(message = "规则类型不能为空", groups = {AddGroup.class, EditGroup.class})
    private String ruleType;

    /**
     * 评估窗口（分钟）
     */
    @NotNull(message = "评估窗口不能为空", groups = {AddGroup.class, EditGroup.class})
    @Min(value = 1, message = "评估窗口至少 1 分钟", groups = {AddGroup.class, EditGroup.class})
    @Max(value = 1440, message = "评估窗口不能超过 1440 分钟", groups = {AddGroup.class, EditGroup.class})
    private Integer windowMinutes;

    /**
     * 连续失败次数阈值（CONSECUTIVE_FAILURE 必填）
     */
    private Integer thresholdCount;

    /**
     * 成功率下限百分比（SUCCESS_RATE 必填，0-100）
     */
    private BigDecimal thresholdRate;

    /**
     * 通知邮箱（多个用逗号或分号分隔）
     */
    @NotBlank(message = "通知邮箱不能为空", groups = {AddGroup.class, EditGroup.class})
    @Size(max = 500, message = "通知邮箱长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String notifyEmails;

    /**
     * 是否启用（Y启用 N停用）
     */
    private String enabled;

    /**
     * 备注
     */
    @Size(max = 500, message = "备注长度不能超过{max}", groups = {AddGroup.class, EditGroup.class})
    private String remark;

}