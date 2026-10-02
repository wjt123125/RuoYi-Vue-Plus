package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * RDS_EXECUTE 组件（rdsExecute）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填，BPM 连接实例
 *   "rdsId": "default",              // 必填，BPM 后台注册的 RDS 数据源 ID
 *   "method": "getMaps",             // 必填：getString/getInt/getLong/getDouble/getMap/getMaps/update/batch
 *   "sql": "select * from t where id=?",  // 字符串；batch 多 SQL 时为字符串数组
 *   "args": ["{{ $.request.id }}"],  // 可选，参数元素为字面量或 {{ $.路径 }} 表达式；batch 批量参数时为数组的数组
 *   "fetchSize": 100,                // 可选，>0 生效
 *   "maxRows": 1000                  // 可选，>0 生效
 * }
 * </pre>
 *
 * <p>响应存 {@code $.<tag>.method} 与 {@code $.<tag>.data}：
 * 标量方法 data 为标量；getMap/getMaps 为 Map/List；update 为影响行数；batch 为每次执行行数数组。
 *
 * @author databus
 */
@Data
public class RdsExecuteCfg {

    /** 连接实例 ID（必填） */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** BPM 后台注册的 RDS 数据源 ID（必填） */
    @DatabusProp(label = "RDS 数据源 ID", required = true, order = 2)
    private String rdsId;

    /** 执行方法：getString/getInt/getLong/getDouble/getMap/getMaps/update/batch（必填） */
    @DatabusProp(
        label = "执行方法", required = true, widget = WidgetKind.SELECT, order = 3,
        options = {
            @DatabusProp.Option(label = "查字符串 getString", value = "getString"),
            @DatabusProp.Option(label = "查整数 getInt", value = "getInt"),
            @DatabusProp.Option(label = "查长整数 getLong", value = "getLong"),
            @DatabusProp.Option(label = "查浮点数 getDouble", value = "getDouble"),
            @DatabusProp.Option(label = "查单行 getMap", value = "getMap"),
            @DatabusProp.Option(label = "查多行 getMaps", value = "getMaps"),
            @DatabusProp.Option(label = "更新 update", value = "update"),
            @DatabusProp.Option(label = "批量执行 batch", value = "batch")
        }
    )
    private String method;

    /** SQL：字符串；batch 多 SQL 模式为字符串数组（必填，SQL 文本原样透传不走路径解析） */
    @DatabusProp(
        label = "SQL 语句", required = true, order = 4,
        description = "SQL 原样透传，参数用 ? 占位写到「SQL 参数」；batch 模式用 JSON 高级模式填字符串数组"
    )
    private Object sql;

    /** 参数：数组/单值；batch 批量参数模式为数组的数组。元素走 resolveParam 解析 */
    @DatabusProp(
        label = "SQL 参数", order = 5,
        description = "JSON 数组，元素为字面量或 {{ $.路径 }} 表达式（与 ? 占位顺序一致）；batch 模式为数组的数组"
    )
    private Object args;

    /** 抓取大小（可选，>0 生效） */
    @DatabusProp(label = "抓取大小", order = 6)
    private Integer fetchSize;

    /** 最大行数（可选，>0 生效） */
    @DatabusProp(label = "最大行数", order = 7)
    private Integer maxRows;
}
