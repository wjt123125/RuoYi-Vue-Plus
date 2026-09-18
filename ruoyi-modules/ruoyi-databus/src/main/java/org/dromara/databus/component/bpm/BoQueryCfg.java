package org.dromara.databus.component.bpm;

import lombok.Data;

import java.util.List;

/**
 * BO_QUERY 组件（boQuery）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填
 *   "main": {
 *     "boName": "BO_EU_API_TEST_MAIN",  // 必填，主表 BO 名称
 *     "method": "list",                 // 可选，默认 list；list / listPage / count
 *     "maxRecord": 100,                 // 可选，大于 0 时先 count 校验，超出报错
 *     "firstRow": 0,                    // method=listPage 必填
 *     "rowCount": 20,                   // method=listPage 必填
 *     "conditionSourcePath": "$.request.conditions"  // 可选，从数据空间读条件列表
 *   },
 *   "relate": [                        // 可选，关联表配置
 *     { "boName": "RelBO", "mainField": "CODE", "relField": "REL_CODE" }
 *   ],
 *   "sub": ["SubBO"]                   // 可选，子表 BO 名称列表（按 BINDID=主表 BINDID 关联）
 * }
 * </pre>
 *
 * <p>条件列表从 conditionSourcePath 读取（List&lt;Map&gt;，键为 fieldName/operator/paramValue/valid），
 * 支持从上游数据动态构造；paramValue 原值透传（类型转换用 fieldMap 预处理）。
 *
 * <p>响应存 {@code $.<tag>.boName / $.<tag>.method / $.<tag>.records}（list/listPage）
 * 或 {@code $.<tag>.count}（count）；records 内嵌关联表/子表数据（键为对应 BO 名称）。
 *
 * @author databus
 */
@Data
public class BoQueryCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 主表查询配置（必填） */
    private MainCfg main;

    /** 关联表配置列表（可选） */
    private List<RelateCfg> relate;

    /** 子表 BO 名称列表（可选） */
    private List<String> sub;

    /**
     * 主表查询配置。
     */
    @Data
    public static class MainCfg {

        /** 主表 BO 名称（必填） */
        private String boName;

        /** 查询方法：list / listPage / count。可选，默认 list */
        private String method;

        /** 最大影响数据量（可选），大于 0 时先 count 校验，超出报错 */
        private Long maxRecord;

        /** 分页起始行（method=listPage 必填） */
        private Integer firstRow;

        /** 分页行数（method=listPage 必填） */
        private Integer rowCount;

        /** 条件列表源路径（可选），从数据空间读取 {@code List<Map>}，键为 fieldName/operator/paramValue/valid */
        private String conditionSourcePath;
    }

    /**
     * 关联表配置。
     */
    @Data
    public static class RelateCfg {

        /** 关联表 BO 名称（必填） */
        private String boName;

        /** 主表关联字段（必填） */
        private String mainField;

        /** 关联表匹配字段（必填） */
        private String relField;
    }
}
