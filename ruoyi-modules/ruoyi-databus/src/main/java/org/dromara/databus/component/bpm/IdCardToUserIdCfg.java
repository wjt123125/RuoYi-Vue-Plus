package org.dromara.databus.component.bpm;

import lombok.Data;

import java.util.List;

/**
 * IDCARD_TO_USERID 组件（idCardToUserId）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填，BPM 连接实例
 *   "fields": [                      // 必填，字段列表（多字段原地写回）
 *     { "path": "$.request.idCards", "separator": "," }
 *   ]
 * }
 * </pre>
 *
 * <p>每个 path 指向一个以 separator 分隔的身份证号字符串；组件按同一 separator 拆分输入，
 * 调 BPM 端批量换 userId 后，再把命中的 userId 按该 separator 连接写回同一路径。
 * 全部未命中抛错；部分未命中告警并写回命中部分。
 *
 * @author databus
 */
@Data
public class IdCardToUserIdCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 字段配置列表（必填） */
    private List<FieldCfg> fields;

    /**
     * 单个字段配置。
     */
    @Data
    public static class FieldCfg {

        /** 身份证号所在 JSONPath（必填），值为 separator 分隔字符串；结果写回同一路径 */
        private String path;

        /** 输入拆分与输出拼接共用的分隔符，可选，默认 ","（支持 | ; 等特殊字符） */
        private String separator;
    }
}
