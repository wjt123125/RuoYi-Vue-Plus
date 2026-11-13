package org.dromara.databus.agent;

import lombok.Data;
import org.dromara.databus.el.bean.ChainInputParam;
import org.dromara.databus.el.bean.CmpProperty;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 链路快照载荷（databus_agent_snapshot.payload 的 JSON 结构）。
 * <p>
 * 只存回滚真正要写回的字段 + 定位信息；el_expression 一并留存便于比对，
 * 回滚时不用它——EL 永远由 {@code IDatabusChainService.updateByBo} 从结构树重新生成。
 * <p>
 * operation=CREATE 时 payload 只有定位信息（chainId/chainCode/chainName），
 * 没有「动手前」结构可写回，回滚语义是删除整条链路。
 *
 * @author databus
 */
@Data
public class AgentChainSnapshot implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 快照所属链路 id
     */
    private Long chainId;

    /**
     * CREATE 新建 / UPDATE 修改
     */
    private String operation;

    /**
     * 链路编码
     */
    private String chainCode;

    /**
     * 链路名称
     */
    private String chainName;

    /**
     * 画布逻辑组件树（回滚写入的权威结构）
     */
    private CmpProperty cmpProperty;

    /**
     * 既有 EL 表达式（仅留存比对，回滚不使用）
     */
    private String elExpression;

    /**
     * 画布 JSON（VueFlow nodes/edges 序列化串）
     */
    private String canvasData;

    /**
     * 链路入参登记表
     */
    private List<ChainInputParam> inputParams;

    /**
     * 执行记录档位
     */
    private String logLevel;

}