package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;

/**
 * BO_UPDATE 组件（boUpdate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填
 *   "boList": [
 *     {
 *       "boName": "UserBO",           // 必填
 *       "sourcePath": "{{ $.request.users }}"  // 必填，要数据表达式，求值 List&lt;Map&gt; 作为 records，
 *                                        // 每条 records 必须含 ID 字段（按记录 ID 定位更新）
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>BPM 端整体事务 all-or-nothing：任一条更新失败全部回滚。
 * 响应存 {@code $.<tag>.boResults} = [{boName, updatedCount}]。
 *
 * @author databus
 */
@Data
public class BoUpdateCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** BO 列表（必填） */
    @DatabusProp(label = "BO 更新列表", required = true, order = 2)
    private List<BoItemCfg> boList;

    /**
     * 单个 BO 项：BO 名称 + 数据空间源路径。
     */
    @Data
    public static class BoItemCfg {

        /** BO 名称（必填） */
        @DatabusProp(label = "BO 名称", required = true, order = 1)
        private String boName;

        /** 数据空间源表达式（必填，要数据，{@code {{ $.路径 }}}），求值 {@code List<Map<String, Object>>} 作为 records，每条必须含 ID */
        @DatabusProp(
            label = "数据空间源表达式", required = true, exprRole = ExprRole.DATA, order = 2,
            placeholder = "{{ $.数据空间.records }}",
            description = "求值 List<Map> 作为 records，每条记录必须含 ID 字段"
        )
        private String sourcePath;
    }
}
