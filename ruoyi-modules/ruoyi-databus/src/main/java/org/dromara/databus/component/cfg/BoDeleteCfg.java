package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;

/**
 * BO_DELETE 组件（boDelete）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填
 *   "method": "remove",              // 可选，默认 remove；remove / removeByBindId
 *   "boList": [
 *     {
 *       "boName": "UserBO",           // 必填
 *       "sourcePath": "{{ $.boQuery1.records }}"  // 必填，要数据表达式，求值 List&lt;Map&gt; 作为 records；
 *                                           // method=remove 每条必须含 ID，
 *                                           // method=removeByBindId 每条必须含 BINDID
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>BPM 端整体事务 all-or-nothing：任一删除失败全部回滚。
 * 响应存 {@code $.<tag>.boResults} = [{boName, removedCount}]。
 *
 * @author databus
 */
@Data
public class BoDeleteCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** 删除方式：remove（按记录 ID）/ removeByBindId（按流程实例 ID）。可选，默认 remove */
    @DatabusProp(
        label = "删除方式", widget = WidgetKind.SELECT, order = 2,
        options = {
            @DatabusProp.Option(label = "按记录 ID 删除 remove", value = "remove"),
            @DatabusProp.Option(label = "按流程实例删除 removeByBindId", value = "removeByBindId")
        }
    )
    private String method;

    /** BO 列表（必填） */
    @DatabusProp(label = "BO 删除列表", required = true, order = 3)
    private List<BoItemCfg> boList;

    /**
     * 单个 BO 项：BO 名称 + 数据空间源路径。
     */
    @Data
    public static class BoItemCfg {

        /** BO 名称（必填） */
        @DatabusProp(label = "BO 名称", required = true, order = 1)
        private String boName;

        /** 数据空间源表达式（必填，要数据，{@code {{ $.路径 }}}），求值 {@code List<Map<String, Object>>} 作为 records */
        @DatabusProp(
            label = "数据空间源表达式", required = true, exprRole = ExprRole.DATA, order = 2,
            description = "remove 每条记录必须含 ID；removeByBindId 每条必须含 BINDID"
        )
        private String sourcePath;
    }
}
