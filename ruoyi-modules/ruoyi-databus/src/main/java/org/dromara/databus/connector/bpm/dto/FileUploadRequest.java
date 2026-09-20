package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * FILE_UPLOAD 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.FileUploadRequest} 字段一致）。
 *
 * <p>对应老系统 Base64ToFileProcessor（旧命名方向相反）。
 *
 * @author databus
 */
@Data
public class FileUploadRequest {

    /** 目标 BO 记录 ID（必填） */
    private String boId;

    /** 应用 ID（必填） */
    private String appId;

    /** BO 定义名（必填） */
    private String boName;

    /** 附件字段名（必填） */
    private String boItemName;

    /** 流程实例 ID（可选） */
    private String processInstId;

    /** 任务实例 ID（可选） */
    private String taskInstId;

    /** 待上传文件列表（必填，至少 1 项） */
    private List<FileUploadItem> files;

    /**
     * 单个待上传文件：fileContent 为 base64。
     * fileSize 仅声明用，BPM 端以实际解码字节数为准。
     */
    @Data
    public static class FileUploadItem {

        /** 文件名（必填） */
        private String fileName;

        /** base64 文件内容（必填） */
        private String fileContent;

        /** 文件安全等级（可选） */
        private Integer securityLevel;

        /** 文件大小声明值（不作为实际大小） */
        private Long fileSize;
    }
}
