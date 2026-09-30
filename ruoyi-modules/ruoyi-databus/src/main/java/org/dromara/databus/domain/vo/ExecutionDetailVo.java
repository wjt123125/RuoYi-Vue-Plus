package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 执行记录详情视图对象：执行级总账 + FULL 档节点明细行。
 * <p>
 * BASIC 档 {@link #nodes} 为空列表；FULL 档节点行按开始时间升序（循环多轮按时间自然排列）。
 *
 * @author databus
 */
@Data
public class ExecutionDetailVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 执行级总账
     */
    private DatabusExecutionVo execution;

    /**
     * 节点明细行（仅 FULL 档有数据）
     */
    private List<DatabusExecutionNodeVo> nodes;

}
