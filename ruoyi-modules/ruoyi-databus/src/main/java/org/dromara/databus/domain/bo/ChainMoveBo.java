package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 链路目录归属移动对象（工作台树拖拽移动链路用）
 *
 * @author databus
 */
@Data
public class ChainMoveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 链路主键
     */
    @NotNull(message = "链路主键不能为空")
    private Long chainId;

    /**
     * 目标目录id（空=移出到未归组）
     */
    private Long directoryId;

}
