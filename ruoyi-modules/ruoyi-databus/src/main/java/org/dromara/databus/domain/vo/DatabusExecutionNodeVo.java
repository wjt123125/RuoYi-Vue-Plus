package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusExecutionNode;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路执行节点记录视图对象 databus_execution_node
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusExecutionNode.class)
public class DatabusExecutionNodeVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 执行记录id
     */
    private Long executionId;

    /**
     * 节点实例id（区分循环多轮同 tag）
     */
    private String nodeInstanceId;

    /**
     * 节点 tag（组件实例标识/数据空间名）
     */
    private String tag;

    /**
     * 组件注册类型名
     */
    private String nodeType;

    /**
     * 节点执行前数据树快照
     */
    private String inputJson;

    /**
     * 节点执行后数据空间快照
     */
    private String outputJson;

    /**
     * 节点状态（SUCCESS/FAILED）
     */
    private String status;

    /**
     * 失败时错误信息
     */
    private String errorMsg;

    /**
     * 节点开始时间
     */
    private LocalDateTime startTime;

    /**
     * 节点结束时间
     */
    private LocalDateTime endTime;

    /**
     * 节点耗时（毫秒）
     */
    private Long duration;

    /**
     * 分支标记（IF 真假/SWITCH 命中 case/循环轮次）
     */
    private String branchInfo;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
