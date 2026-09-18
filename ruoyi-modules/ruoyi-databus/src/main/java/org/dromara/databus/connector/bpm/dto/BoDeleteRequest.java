package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * BO_DELETE 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.BoDeleteRequest} 字段一致）。
 * <p>
 * 按 method 两种删除模式：
 * <ul>
 *   <li><b>remove</b>（默认）：按记录 ID 逐条删除，records 每条必须含 ID</li>
 *   <li><b>removeByBindId</b>：按流程实例 bindId 批量删除该流程下所有 BO 数据，
 *       records 每条必须含 BINDID（BPM 端自动去重后逐个 bindId 删除）</li>
 * </ul>
 *
 * @author databus
 */
@Data
public class BoDeleteRequest {

    /** 删除方式：remove（按记录 ID）/ removeByBindId（按流程实例 ID）。可选，默认 remove */
    private String method = "remove";

    /** BO 列表，必填。每项含 boName + records */
    private List<BoItem> boList;
}
