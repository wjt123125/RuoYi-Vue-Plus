package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI agent 每一轮动手前的链路快照 databus_agent_snapshot
 * <p>
 * 只增不改：一行 = 某一轮 chain_save 动手前的链路全量快照（cmpProperty / elExpression /
 * canvasData / inputParams / logLevel），供「回到 AI 动手前」按轮次回滚（设计文档 §9.2 / §16.1）。
 * payload 用 mediumtext：大链路的结构树 + 画布 JSON 可能超过 64KB，截断的快照等于回滚不了。
 *
 * @author databus
 */
@Data
@TableName("databus_agent_snapshot")
public class DatabusAgentSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 所属 run
     */
    private String runId;

    /**
     * 第几轮动手前（1 起；新建轮也占一轮，回滚时按 operation=CREATE 删除整条链路）
     */
    private Integer round;

    /**
     * CREATE 新建 / UPDATE 修改
     */
    private String operation;

    /**
     * 链路全量快照（JSON：cmpProperty/elExpression/canvasData/inputParams/logLevel）
     */
    private String payload;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}