package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * BO_CREATE 端点入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.BoCreateRequest} 字段一致）。
 *
 * <p>所有字段从 HTTP 请求体显式传入（决策 1：上下文原子化）。
 * boList 中每条 record 已经是 BPM 字段名 + 正确类型（决策 3：字段重映射 alias + type 转换
 * 在 Component 层 fieldMap 增强完成，BPM 端不做）。
 *
 * <p>BPM 端不做回写策略（决策 2：6 种策略 no/all/boId/add/exclude/include 搬到 Connector 层），
 * 只返回 boResults 含生成的 ID 供 Connector 层按策略回写。
 *
 * @author databus
 */
@Data
public class BoCreateRequest {

    /** 创建方法：create（流程驱动，需 bindId）/ createDataBO（纯数据，需 UserContext）。可选，默认 create */
    private String method = "create";

    /** bindId，method=create 时必填 */
    private String bindId;

    /** 用户 ID，必填（用于构造 UserContext） */
    private String uid;

    /** BO 列表，必填。每项含 boName + records */
    private List<BoItem> boList;
}
