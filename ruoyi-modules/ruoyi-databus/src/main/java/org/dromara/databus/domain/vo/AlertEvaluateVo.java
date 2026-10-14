package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 告警规则评估结果视图对象（手动评估端点与定时任务共用）。
 *
 * @author databus
 */
@Data
public class AlertEvaluateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 本次扫描的启用规则数
     */
    private int ruleCount;

    /**
     * 新触发（插入 FIRING 记录）的规则数
     */
    private int firedCount;

    /**
     * 恢复（翻转 FIRING 记录为 RECOVERED）的规则数
     */
    private int recoveredCount;

    /**
     * 触发通知发送成功数
     */
    private int notifySuccess;

    /**
     * 触发通知发送失败数（含邮件通道未启用）
     */
    private int notifyFailed;

    /**
     * 评估总耗时（毫秒）
     */
    private long costMillis;

}