package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 数据总线告警记录分页查询业务对象。
 * <p>
 * 只读查询：规则名/链路编码模糊，状态/类型精确，触发时间区间直传字符串
 * （格式 {@code yyyy-MM-dd HH:mm:ss}，MySQL between 可直接比较）。
 *
 * @author databus
 */
@Data
public class DatabusAlertRecordBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 规则名称（模糊匹配）
     */
    private String ruleName;

    /**
     * 目标链路编码（模糊匹配）
     */
    private String chainCode;

    /**
     * 告警类型（CONSECUTIVE_FAILURE / SUCCESS_RATE，精确匹配）
     */
    private String alertType;

    /**
     * 状态（FIRING / RECOVERED，精确匹配）
     */
    private String status;

    /**
     * 触发时间区间-起（yyyy-MM-dd HH:mm:ss）
     */
    private String beginTime;

    /**
     * 触发时间区间-止（yyyy-MM-dd HH:mm:ss）
     */
    private String endTime;

}