package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * BO_CREATE 请求入参（对应 BPM 端 {@code BoCreateRequest}）。
 * <p>
 * 所有字段从 Component 层显式传入（决策 §1：上下文原子化）。
 * <p>
 * BPM 端不做回写策略（决策 §2：6 种策略 no/all/boId/add/exclude/include 搬到 Connector 层），
 * 只返回 boResults 含生成的 ID 供 Component 层按策略回写。
 *
 * @author databus
 */
@Data
public class BpmBoCreateParam {

    /** 创建方法：create（流程驱动，需 bindId）/ createDataBO（纯数据，需 UserContext）。可选，默认 create */
    private String method = "create";

    /** bindId，method=create 时必填 */
    private String bindId;

    /** 用户 ID，必填（用于构造 UserContext） */
    private String uid;

    /** BO 列表，必填。每项含 boName + records */
    private List<BpmBoItemParam> boList;
}
