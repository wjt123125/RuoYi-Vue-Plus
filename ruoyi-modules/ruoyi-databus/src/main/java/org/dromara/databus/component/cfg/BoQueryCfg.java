package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

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
 *     "conditionSourcePath": "{{ $.request.conditions }}"  // 可选，要数据表达式，求值条件列表
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
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 主表查询配置（必填） */
    @DatabusProp(label = "主表查询", required = true, order = 2)
    private MainCfg main;

    /** 关联表配置列表（可选） */
    @DatabusProp(label = "关联表配置", order = 3)
    private List<RelateCfg> relate;

    /** 子表 BO 名称列表（可选） */
    @DatabusProp(label = "子表 BO 名称", order = 4, description = "按 BINDID=主表 BINDID 挂载，输入后回车添加")
    private List<String> sub;

    /**
     * 主表查询配置。
     */
    @Data
    public static class MainCfg {

        /** 主表 BO 名称（必填） */
        @DatabusProp(label = "主表 BO 名称", required = true, order = 1)
        private String boName;

        /** 查询方法：list / listPage / count。可选，默认 list */
        @DatabusProp(
            label = "查询方法", widget = WidgetKind.SELECT, order = 2,
            options = {
                @DatabusProp.Option(label = "列表 list", value = "list"),
                @DatabusProp.Option(label = "分页 listPage", value = "listPage"),
                @DatabusProp.Option(label = "计数 count", value = "count")
            }
        )
        private String method;

        /** 最大影响数据量（可选），大于 0 时先 count 校验，超出报错 */
        @DatabusProp(label = "最大数据量校验", order = 3, description = ">0 时先 count 校验，超出报错")
        private Long maxRecord;

        /** 分页起始行（method=listPage 必填） */
        @DatabusProp(label = "分页起始行 firstRow", order = 4, showWhen = @DatabusProp.ShowWhen(field = "method", eq = "listPage"))
        private Integer firstRow;

        /** 分页行数（method=listPage 必填） */
        @DatabusProp(label = "分页行数 rowCount", order = 5, showWhen = @DatabusProp.ShowWhen(field = "method", eq = "listPage"))
        private Integer rowCount;

        /** 条件列表源表达式（可选，要数据，{@code {{ $.路径 }}}），求值 {@code List<Map>}，键为 fieldName/operator/paramValue/valid */
        @DatabusProp(
            label = "动态条件列表路径", exprRole = ExprRole.DATA, order = 6,
            description = "求值条件列表 List<Map>（键 fieldName/operator/paramValue/valid），可由上游动态构造"
        )
        private String conditionSourcePath;
    }

    /**
     * 关联表配置。
     */
    @Data
    public static class RelateCfg {

        /** 关联表 BO 名称（必填） */
        @DatabusProp(label = "关联表 BO 名称", required = true, order = 1)
        private String boName;

        /** 主表关联字段（必填） */
        @DatabusProp(label = "主表关联字段", required = true, order = 2)
        private String mainField;

        /** 关联表匹配字段（必填） */
        @DatabusProp(label = "关联表匹配字段", required = true, order = 3)
        private String relField;
    }
}
