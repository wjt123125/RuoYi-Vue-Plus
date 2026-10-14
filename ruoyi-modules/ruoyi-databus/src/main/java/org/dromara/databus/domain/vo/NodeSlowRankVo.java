package org.dromara.databus.domain.vo;

import lombok.Data;

/**
 * 节点耗时排行榜行（窗口内按平均耗时倒序）。
 *
 * @author databus
 */
@Data
public class NodeSlowRankVo {

    /**
     * 链路编码。
     */
    private String chainCode;

    /**
     * 节点标签（组件 tag）。
     */
    private String tag;

    /**
     * 节点类型（组件类名/节点类型）。
     */
    private String nodeType;

    /**
     * 窗口内节点调用次数。
     */
    private Integer totalCount;

    /**
     * 窗口内节点失败次数。
     */
    private Integer failedCount;

    /**
     * 窗口内加权平均耗时（毫秒）；无样本时为 null。
     */
    private Long avgDuration;

    /**
     * 窗口内最大耗时（毫秒）。
     */
    private Long maxDuration;

}