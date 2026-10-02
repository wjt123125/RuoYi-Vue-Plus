package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * FILE_DOWNLOAD 组件（fileDownload）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",       // 必填，BPM 连接实例
 *   "boId": "{{ $.boCreate1.boId }}",        // 必填，BO 记录 ID（表达式/字面量）
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
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** BO 记录 ID（必填，字面量或 {@code {{ $.路径 }}} 表达式） */
    @DatabusProp(
        label = "BO 记录 ID", required = true, exprRole = ExprRole.DATA, order = 2,
        placeholder = "{{ $.boCreate1.boId }}"
    )
    private String boId;

    /** 附件字段名（必填，字面量） */
    @DatabusProp(label = "附件字段名", required = true, order = 3, placeholder = "BO_FIELD_XXX")
    private String fieldName;
}
