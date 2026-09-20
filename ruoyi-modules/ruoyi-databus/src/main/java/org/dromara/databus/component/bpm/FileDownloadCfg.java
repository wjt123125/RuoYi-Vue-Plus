package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * FILE_DOWNLOAD 组件（fileDownload）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",       // 必填，BPM 连接实例
 *   "boId": "$.boCreate1.boId",          // 必填，BO 记录 ID（路径/字面量）
 *   "fieldName": "BO_FIELD_FILE"         // 必填，附件字段名（字面量）
 * }
 * </pre>
 *
 * <p>响应存 {@code $.<tag>.fileCount} 与 {@code $.<tag>.files}
 * （含 base64 的完整文件列表，可直接作为 fileUpload 的 sourcePath 输入）。
 *
 * @author databus
 */
@Data
public class FileDownloadCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** BO 记录 ID（必填，走参数解析） */
    private String boId;

    /** 附件字段名（必填，字面量） */
    private String fieldName;
}
