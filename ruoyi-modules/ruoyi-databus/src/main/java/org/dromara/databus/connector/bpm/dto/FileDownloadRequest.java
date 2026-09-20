package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

/**
 * FILE_DOWNLOAD 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.FileDownloadRequest} 字段一致）。
 *
 * <p>对应老系统 FileToBase64Processor（旧命名方向相反）。
 *
 * @author databus
 */
@Data
public class FileDownloadRequest {

    /** BO 记录 ID（必填） */
    private String boId;

    /** 附件字段名（必填） */
    private String fieldName;
}
