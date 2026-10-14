package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

/**
 * 数据总线告警记录对象 databus_alert_record。
 * <p>
 * 一次告警生命周期一行：触发时插入（status=FIRING，同时记下规则名/链路/收件人快照与通知结果），
 * 恢复时把该行翻转为 RECOVERED 并回填 recoverTime。持续触发/持续正常期间不新增行，
 * 评估任务以「最近一行是否 FIRING」判定当前状态，实现去重与恢复配对。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_alert_record")
public class DatabusAlertRecord extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 规则id
     */
    private Long ruleId;

    /**
     * 规则名称（触发时快照，规则删除后仍可追溯）
     */
    private String ruleName;

    /**
     * 目标链路编码（触发时快照）
     */
    private String chainCode;

    /**
     * 告警类型（触发时快照，见 AlertRuleTypeEnum）
     */
    private String alertType;

    /**
     * 状态（FIRING / RECOVERED，见 AlertStatusEnum）
     */
    private String status;

    /**
     * 告警摘要（命中条件的可读描述）
     */
    private String message;

    /**
     * 通知对象（触发时快照，逗号/分号分隔的邮箱）
     */
    private String notifyEmails;

    /**
     * 通知结果（SUCCESS / FAILED / SKIPPED，见 AlertNotifyStatusEnum）
     */
    private String notifyStatus;

    /**
     * 触发时间
     */
    private LocalDateTime triggerTime;

    /**
     * 恢复时间（FIRING 时为空）
     */
    private LocalDateTime recoverTime;

}