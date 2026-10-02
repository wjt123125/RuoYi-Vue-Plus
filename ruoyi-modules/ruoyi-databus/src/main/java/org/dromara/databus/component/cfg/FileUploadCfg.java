package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * FILE_UPLOAD 组件（fileUpload）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",              // 必填，BPM 连接实例
 *   "sourcePath": "{{ $.request.files }}",       // 必填，要数据表达式，求值文件数组
 *   "boId": "{{ $.boCreate1.boId }}",            // 必填，目标 BO 记录 ID（表达式/字面量）
 *   "appId": "com.awspaas.user.apps.demo",      // 必填
 *   "boName": "BO_DEMO_MAIN",                   // 必填
 *   "boItemName": "BO_FIELD_FILE",              // 必填，附件字段名
 *   "processInstId": "{{ $.processStart1.processInstanceId }}", // 可选
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
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 文件数组表达式（必填，要数据，{@code {{ $.路径 }}}） */
    @DatabusProp(
        label = "文件数组表达式", required = true, exprRole = ExprRole.DATA, order = 2,
        placeholder = "{{ $.数据空间.files }}",
        description = "求值结果为文件数组，每项含 fileName 与 base64 的 fileContent；常接 fileDownload 的输出"
    )
    private String sourcePath;

    /** 目标 BO 记录 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(label = "目标 BO 记录 ID", required = true, exprRole = ExprRole.DATA, order = 3)
    private String boId;

    /** 应用 ID（必填） */
    @DatabusProp(label = "应用 ID", required = true, order = 4, placeholder = "com.awspaas.user.apps.xxx")
    private String appId;

    /** BO 定义名（必填） */
    @DatabusProp(label = "BO 定义名", required = true, order = 5, placeholder = "BO_XXX")
    private String boName;

    /** 附件字段名（必填） */
    @DatabusProp(label = "附件字段名", required = true, order = 6, placeholder = "BO_FIELD_XXX")
    private String boItemName;

    /** 流程实例 ID（可选，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(label = "流程实例 ID", exprRole = ExprRole.DATA, order = 7)
    private String processInstId;

    /** 任务实例 ID（可选，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(label = "任务实例 ID", exprRole = ExprRole.DATA, order = 8)
    private String taskInstId;

    /** true 时要求每个文件都携带摘要校验信息，缺省 false */
    @DatabusProp(
        label = "校验文件摘要", widget = WidgetKind.BOOLEAN, order = 9,
        description = "开启后每个文件必须携带 checkMethod（md5/sha1/sha256/sha512）与 checksum"
    )
    private Boolean validateChecksum;
}
