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
 * <p>每个 path 指向一个逗号分隔的身份证号字符串；组件调 BPM 端批量换 userId 后，
 * 把命中的 userId 按 separator 连接写回同一路径。全部未命中抛错；部分未命中告警并写回命中部分。
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

        /** 身份证号所在 JSONPath（必填），值为逗号分隔字符串；结果写回同一路径 */
        private String path;

        /** 输出 userId 连接符，可选，默认 "," */
        private String separator;
    }
}
