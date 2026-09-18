package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * BO_QUERY 请求入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.BoQueryRequest} 字段一致）。
 * <p>
 * 对应老系统 BoQueryProcessor 的 main（主表查询）+ relate（关联表）+ sub（子表）三段配置。
 * 查询条件 paramValue 为 Object 原值透传（类型转换由 Component 层完成）。
 *
 * @author databus
 */
@Data
public class BoQueryRequest {

    /** 主表查询配置，必填 */
    private MainQuery main;

    /** 关联表配置列表，可选。对主表每条记录按 mainField=relField 关联查询并挂到记录的关联表名键下 */
    private List<RelateQuery> relate;

    /** 子表 BO 名称列表，可选。对主表每条记录按子表 BINDID=主表 BINDID 查询并挂到记录的子表名键下 */
    private List<String> sub;

    /**
     * 主表查询配置。
     */
    @Data
    public static class MainQuery {

        /** 主表 BO 名称，必填 */
        private String boName;

        /** 查询方法：list（全量）/ listPage（分页）/ count（计数）。可选，默认 list */
        private String method = "list";

        /** 最大影响数据量，可选。大于 0 时先 count 校验，超出抛 BPM_BO_QUERY_MAX_RECORD_EXCEEDED */
        private Long maxRecord;

        /** 分页起始行（method=listPage 必填） */
        private Integer firstRow;

        /** 分页行数（method=listPage 必填） */
        private Integer rowCount;

        /** 查询条件列表，可选 */
        private List<QueryCondition> conditions;
    }

    /**
     * 单个查询条件。valid=false 的条件跳过（老系统语义保持）。
     */
    @Data
    public static class QueryCondition {

        /** 字段名，必填 */
        private String fieldName;

        /** 操作符：= / != / &gt; / &lt; / like / is null / is not null 等，必填 */
        private String operator;

        /** 条件值（Object 原值透传；is null / is not null 时忽略） */
        private Object paramValue;

        /** valid=false 时跳过该条件。可选，默认 true */
        private Boolean valid;
    }

    /**
     * 关联表查询配置。
     */
    @Data
    public static class RelateQuery {

        /** 关联表 BO 名称，必填 */
        private String boName;

        /** 主表关联字段，必填 */
        private String mainField;

        /** 关联表匹配字段，必填 */
        private String relField;
    }
}
