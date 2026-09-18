package org.dromara.databus.component.bpm;

import lombok.Data;

import java.util.List;

/**
 * BO_UPDATE 组件（boUpdate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",   // 必填
 *   "boList": [
 *     {
 *       "boName": "UserBO",           // 必填
 *       "sourcePath": "$.request.users"  // 必填，数据空间路径，读取 List&lt;Map&gt; 作为 records，
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
    private String connectionId;

    /** BO 列表（必填） */
    private List<BoItemCfg> boList;

    /**
     * 单个 BO 项：BO 名称 + 数据空间源路径。
     */
    @Data
    public static class BoItemCfg {

        /** BO 名称（必填） */
        private String boName;

        /** 数据空间源路径（必填），读取 {@code List<Map<String, Object>>} 作为 records，每条必须含 ID */
        private String sourcePath;
    }
}
