package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 脚本组件运行时注册健康项（台账页露出启动期编译/注册失败件用）。
 *
 * @author databus
 */
@Data
public class ScriptRuntimeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 组件主键
     */
    private Long componentId;

    /**
     * 组件编码（FlowBus 注册 id）
     */
    private String componentCode;

    /**
     * 已注册版本（主表 version）
     */
    private Integer version;

    /**
     * 运行时是否健康（true=已成功注册进 FlowBus；false=启动期编译/注册失败，链路执行将报错）
     */
    private Boolean healthy;

    /**
     * 失败原因（healthy=false 时有值）
     */
    private String error;

    /**
     * 最近注册时间
     */
    private LocalDateTime registeredAt;

    /**
     * 最近失败时间
     */
    private LocalDateTime failedAt;

}
