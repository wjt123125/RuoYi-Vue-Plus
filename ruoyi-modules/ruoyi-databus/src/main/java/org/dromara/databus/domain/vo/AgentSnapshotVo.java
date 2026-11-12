package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI agent 快照返回对象。
 * <p>
 * 只回元信息与链路 id，不回 payload：列表接口给「回到 AI 动手前」按轮次选择用，
 * 大 payload 逐条下发没有意义；真正回滚由服务端按 runId + round 取正本。
 *
 * @author databus
 */
@Data
public class AgentSnapshotVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    private Long id;

    /**
     * 所属 run
     */
    private String runId;

    /**
     * 第几轮动手前
     */
    private Integer round;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 快照所属链路 id（服务端从 payload 里解析补充）
     */
    private Long chainId;

    /**
     * 快照所属链路名称（服务端从 payload 里解析补充）
     */
    private String chainName;

}