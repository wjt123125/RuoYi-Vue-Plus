package org.dromara.databus.component.data;

import lombok.Data;

import java.util.Map;

/**
 * 数据补丁组件（dataPatch）的节点参数。
 * <pre>
 * { "target": "$.boQuery1.records[*]",
 *   "patch": { "BO_FIELD_USER": "$.request.newUser", "BO_FIELD_NUM": 99 } }
 * </pre>
 *
 * <p>语义为 JSON Merge Patch：把 {@code patch} 中声明的字段合并到 {@code target}
 * 命中的每个对象上，未声明的字段（如 BO 记录的 ID）原样保留；目标对象缺少的
 * 字段自动新增，嵌套对象做深合并。
 *
 * <ul>
 *   <li>{@code target} 为完整 JSONPath，支持 {@code [*]} 全量、{@code [i]} 索引、
 *       {@code [?(...)]} 过滤（组件 read 出命中的对象引用后逐个深合并），
 *       必须指向对象或对象数组。</li>
 *   <li>{@code patch} 为补丁对象：键是相对 target 的字段名，支持嵌套对象
 *       （递归深合并）；叶子值走统一参数解析（常量 / 裸路径 / 混合模板），
 *       数组作为整体叶子值写入。</li>
 * </ul>
 *
 * @author databus
 */
@Data
public class DataPatchCfg {

    /**
     * 补丁目标 JSONPath（必填），指向待打补丁的对象或对象数组。
     */
    private String target;

    /**
     * merge 补丁对象（必填，非空）：键为字段名（支持嵌套），叶子值支持常量 / 裸路径 / 混合模板。
     */
    private Map<String, Object> patch;
}
