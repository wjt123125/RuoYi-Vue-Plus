package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 回退预检结果：先算清会发生什么，再由用户决定是否执行。
 * <p>
 * 三份清单互不重叠：
 * <ul>
 *   <li>revert：将撤销的资源（删除新建 / 写回改前内容）</li>
 *   <li>skip：已采纳、已发布或已被修改，撤不掉但会被跳过的资源（附原因）</li>
 *   <li>affected：引用了「将被删除资源」的链路，删除后需要重新编排</li>
 * </ul>
 * 三份都为空表示这一轮没有可回退的改动。
 *
 * @author databus
 */
@Data
public class AgentRevertPreviewVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 将撤销
     */
    private List<AgentRollbackVo> revert = new ArrayList<>();

    /**
     * 将跳过（附原因）
     */
    private List<AgentRollbackVo> skip = new ArrayList<>();

    /**
     * 受影响的引用方链路
     */
    private List<AgentRollbackVo> affected = new ArrayList<>();

}