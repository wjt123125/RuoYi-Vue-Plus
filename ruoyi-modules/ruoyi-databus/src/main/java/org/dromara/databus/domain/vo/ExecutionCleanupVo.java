package org.dromara.databus.domain.vo;

import lombok.Data;

/**
 * 执行记录保留期清理结果（定时任务日志与手动端点返回共用）。
 *
 * @author databus
 */
@Data
public class ExecutionCleanupVo {

    /**
     * 本次实际生效的保留天数（手动端点覆盖优先，否则取配置）。
     */
    private Integer retentionDays;

    /**
     * 删除的总账条数（databus_execution）。
     */
    private Long executionDeleted;

    /**
     * 删除的节点明细行数（databus_execution_node）。
     */
    private Long nodeDeleted;

    /**
     * 是否因达到 maxRounds 上限被截断（true＝仍有过期记录，下轮继续）。
     */
    private Boolean truncated;

}
