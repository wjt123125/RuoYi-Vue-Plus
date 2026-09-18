package org.dromara.databus.component.bpm;

import lombok.Data;

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
 *       "sourcePath": "$.boQuery1.records"  // 必填，读取 List&lt;Map&gt; 作为 records；
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
    private String connectionId;

    /** 删除方式：remove（按记录 ID）/ removeByBindId（按流程实例 ID）。可选，默认 remove */
    private String method;

    /** BO 列表（必填） */
    private List<BoItemCfg> boList;

    /**
     * 单个 BO 项：BO 名称 + 数据空间源路径。
     */
    @Data
    public static class BoItemCfg {

        /** BO 名称（必填） */
        private String boName;

        /** 数据空间源路径（必填），读取 {@code List<Map<String, Object>>} 作为 records */
        private String sourcePath;
    }
}
