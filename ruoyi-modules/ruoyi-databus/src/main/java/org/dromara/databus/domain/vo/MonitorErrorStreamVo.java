package org.dromara.databus.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 实时错误流条目（窗口无限制，取最近若干条失败执行记录）。
 * <p>
 * 直接读原始总账 databus_execution（非宽表），保证「实时」——无需等整点聚合。
 *
 * @author databus
 */
@Data
public class MonitorErrorStreamVo {

    /**
     * 执行记录id。
     */
    private Long id;

    /**
     * 链路编码。
     */
    private String chainCode;

    /**
     * 执行状态（流内固定为 FAILED，前端配色用）。
     */
    private String status;

    /**
     * 错误信息。
     */
    private String errorMsg;

    /**
     * 执行开始时间。
     */
    private LocalDateTime startTime;

    /**
     * 执行耗时（毫秒）。
     */
    private Long duration;

}