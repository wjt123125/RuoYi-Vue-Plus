package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 「回到这次对话前」的回滚结果：被回滚的链路及是否整条删除。
 *
 * @author databus
 */
@Data
public class AgentRollbackVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 被回滚的链路 id
     */
    private Long chainId;

    /**
     * true 表示本对话新建的链路已整条删除；false 表示结构已写回对话前
     */
    private Boolean deleted;

    public AgentRollbackVo() {
    }

    public AgentRollbackVo(Long chainId, Boolean deleted) {
        this.chainId = chainId;
        this.deleted = deleted;
    }

}