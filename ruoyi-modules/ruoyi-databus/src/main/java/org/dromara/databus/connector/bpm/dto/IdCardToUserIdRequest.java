package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * IDCARD_TO_USERID 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.IdCardToUserIdRequest} 字段一致）。
 * <p>
 * 对应老系统 IdCardToUserIdProcessor：按身份证号查 BPM ORGUSER.EXT1 取 userid。
 *
 * @author databus
 */
@Data
public class IdCardToUserIdRequest {

    /** 身份证号列表（必填），逐个查询 */
    private List<String> idCards;

    /** 命中 userId 的输出连接符，可选，默认 "," */
    private String separator;
}
