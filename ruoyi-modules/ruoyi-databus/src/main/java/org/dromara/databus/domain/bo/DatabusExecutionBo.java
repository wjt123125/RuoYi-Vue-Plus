package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路执行记录分页查询业务对象。
 * <p>
 * 列表只做只读查询：链路编码模糊、状态精确、开始时间区间（字符串直传，
 * 格式 {@code yyyy-MM-dd HH:mm:ss}，MySQL between 可直接比较）。
 *
 * @author databus
 */
@Data
public class DatabusExecutionBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 链路编码（模糊匹配）
     */
    private String chainCode;

    /**
     * 执行状态（RUNNING/SUCCESS/FAILED，精确匹配）
     */
    private String status;

    /**
     * 开始时间区间-起（按 start_time 过滤，yyyy-MM-dd HH:mm:ss）
     */
    private String beginTime;

    /**
     * 开始时间区间-止（按 start_time 过滤，yyyy-MM-dd HH:mm:ss）
     */
    private String endTime;

}
