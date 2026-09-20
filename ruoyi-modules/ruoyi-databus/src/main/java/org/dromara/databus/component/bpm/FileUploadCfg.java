package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * FILE_UPLOAD 组件（fileUpload）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",              // 必填，BPM 连接实例
 *   "sourcePath": "$.request.files",            // 必填，数据空间中的文件数组路径
 *   "boId": "$.boCreate1.boId",                 // 必填，目标 BO 记录 ID（路径/字面量）
 *   "appId": "com.awspaas.user.apps.demo",      // 必填
 *   "boName": "BO_DEMO_MAIN",                   // 必填
 *   "boItemName": "BO_FIELD_FILE",              // 必填，附件字段名
 *   "processInstId": "$.processStart1.processInstanceId", // 可选
 *   "taskInstId": "",                           // 可选
 *   "validateChecksum": true                    // 可选，true 时每个文件必须携带 checkMethod+checksum
 * }
 * </pre>
 *
 * <p>sourcePath 数组每项：fileName/fileContent(base64) 必填，securityLevel/fileSize 可选，
 * 可选 checkMethod(md5/sha1/sha256/sha512) + checksum(十六进制期望摘要)。
 *
 * <p>响应存 {@code $.<tag>.uploadedCount} 与 {@code $.<tag>.files}。
 *
 * @author databus
 */
@Data
public class FileUploadCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 文件数组在数据空间中的路径（必填） */
    private String sourcePath;

    /** 目标 BO 记录 ID（必填，走参数解析） */
    private String boId;

    /** 应用 ID（必填） */
    private String appId;

    /** BO 定义名（必填） */
    private String boName;

    /** 附件字段名（必填） */
    private String boItemName;

    /** 流程实例 ID（可选，走参数解析） */
    private String processInstId;

    /** 任务实例 ID（可选，走参数解析） */
    private String taskInstId;

    /** true 时要求每个文件都携带摘要校验信息，缺省 false */
    private Boolean validateChecksum;
}
