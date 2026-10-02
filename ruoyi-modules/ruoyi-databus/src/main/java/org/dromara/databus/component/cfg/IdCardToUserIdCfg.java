package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;

/**
 * IDCARD_TO_USERID 组件（idCardToUserId）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填，BPM 连接实例
 *   "fields": [                      // 必填，字段列表（多字段原地写回）
 *     { "path": "{{ $.request.idCards }}", "separator": "," }
 *   ]
 * }
 * </pre>
 *
 * <p>每个 path 表达式（要数据，{@code {{ $.路径 }}}）指向一个以 separator 分隔的身份证号字符串；
 * 组件按同一 separator 拆分输入，调 BPM 端批量换 userId 后，再把命中的 userId
 * 按该 separator 连接写回解包后的同一路径。
 * 全部未命中抛错；部分未命中告警并写回命中部分。
 *
 * @author databus
 */
@Data
public class IdCardToUserIdCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 字段配置列表（必填） */
    @DatabusProp(label = "换 ID 字段列表", required = true, order = 2)
    private List<FieldCfg> fields;

    /**
     * 单个字段配置。
     */
    @Data
    public static class FieldCfg {

        /**
         * 身份证号表达式（必填，要数据，{@code {{ $.路径 }}}），值为 separator 分隔字符串；
         * 结果写回解包后的同一路径
         */
        @DatabusProp(
            label = "身份证号表达式", required = true, exprRole = ExprRole.DATA, order = 1,
            description = "求值为分隔符拼接的身份证号字符串，换得的 userId 原地写回同一路径"
        )
        private String path;

        /** 输入拆分与输出拼接共用的分隔符，可选，默认 ","（支持 | ; 等特殊字符） */
        @DatabusProp(label = "分隔符", order = 2, placeholder = ",")
        private String separator;
    }
}
