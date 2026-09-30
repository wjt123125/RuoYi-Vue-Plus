package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusExecution;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路执行记录视图对象 databus_execution
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusExecution.class)
public class DatabusExecutionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 执行记录id
     */
    private Long id;

    /**
     * 链路id
     */
    private Long chainId;

    /**
     * 链路编码
     */
    private String chainCode;

    /**
     * 执行入参 JSON（详情抽屉展示 + 重跑取此还原）
     */
    private String requestData;

    /**
     * 最终输出 JSON
     */
    private String responseData;

    /**
     * 执行状态（RUNNING/SUCCESS/FAILED）
     */
    private String status;

    /**
     * 失败时错误信息
     */
    private String errorMsg;

    /**
     * 执行开始时间
     */
    private LocalDateTime startTime;

    /**
     * 执行结束时间
     */
    private LocalDateTime endTime;

    /**
     * 执行耗时（毫秒）
     */
    private Long duration;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
