package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 组件目录归属移动对象（工作区统一资源树拖拽移动组件用）
 *
 * @author databus
 */
@Data
public class ComponentMoveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 组件主键
     */
    @NotNull(message = "组件主键不能为空")
    private Long componentId;

    /**
     * 目标目录id（空=移出到未归组）
     */
    private Long directoryId;

}
