package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusAlertRecord;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据总线告警记录视图对象 databus_alert_record
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusAlertRecord.class)
public class DatabusAlertRecordVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键 id
     */
    private Long id;

    /**
     * 规则id
     */
    private Long ruleId;

    /**
     * 规则名称（触发时快照）
     */
    private String ruleName;

    /**
     * 目标链路编码
     */
    private String chainCode;

    /**
     * 告警类型
     */
    private String alertType;

    /**
     * 状态（FIRING / RECOVERED）
     */
    private String status;

    /**
     * 告警摘要
     */
    private String message;

    /**
     * 通知对象
     */
    private String notifyEmails;

    /**
     * 通知结果（SUCCESS / FAILED / SKIPPED）
     */
    private String notifyStatus;

    /**
     * 触发时间
     */
    private LocalDateTime triggerTime;

    /**
     * 恢复时间
     */
    private LocalDateTime recoverTime;

}