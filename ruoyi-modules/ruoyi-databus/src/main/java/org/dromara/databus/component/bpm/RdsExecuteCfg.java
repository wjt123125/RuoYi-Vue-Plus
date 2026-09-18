package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * RDS_EXECUTE 组件（rdsExecute）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填，BPM 连接实例
 *   "rdsId": "default",              // 必填，BPM 后台注册的 RDS 数据源 ID
 *   "method": "getMaps",             // 必填：getString/getInt/getLong/getDouble/getMap/getMaps/update/batch
 *   "sql": "select * from t where id=?",  // 字符串；batch 多 SQL 时为字符串数组
 *   "args": ["$.request.id"],        // 可选，参数（元素走统一参数解析：路径/模板/常量）；batch 批量参数时为数组的数组
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
    private String connectionId;

    /** BPM 后台注册的 RDS 数据源 ID（必填） */
    private String rdsId;

    /** 执行方法：getString/getInt/getLong/getDouble/getMap/getMaps/update/batch（必填） */
    private String method;

    /** SQL：字符串；batch 多 SQL 模式为字符串数组（必填，SQL 文本原样透传不走路径解析） */
    private Object sql;

    /** 参数：数组/单值；batch 批量参数模式为数组的数组。元素走 resolveParam 解析 */
    private Object args;

    /** 抓取大小（可选，>0 生效） */
    private Integer fetchSize;

    /** 最大行数（可选，>0 生效） */
    private Integer maxRows;
}
