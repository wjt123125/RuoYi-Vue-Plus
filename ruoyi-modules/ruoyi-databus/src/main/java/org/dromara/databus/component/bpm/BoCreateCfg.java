package org.dromara.databus.component.bpm;

import lombok.Data;

import java.util.List;

/**
 * BO_CREATE 组件（boCreate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "method": "create",            // 可选，默认 create；create / createDataBO
 *   "bindId": "bo-001",            // method=create 时必填
 *   "uid": "admin",                // 必填
 *   "boList": [
 *     {
 *       "boName": "UserBO",         // 必填
 *       "sourcePath": "$.request.users",  // 必填，数据空间路径，读取 List<Map> 作为 records
 *       "rewrite": {                // 可选，缺省策略 no
 *         "strategy": "all",        // no / all / boId / add / exclude / include
 *         "path": "$.response.users",  // 回写目标路径
 *         "addFields": ["ID"],        // add / boId 用
 *         "excludes": ["password"],   // exclude 用
 *         "includes": ["ID", "name"]  // include 用（为空退化为 all）
 *       }
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>6 回写策略语义（与老系统 BoCreateProcessor 一致，详见
 * <a href="../../../../../../../../../../../docs/wiki/databus-bpm-connector-service-design.md">
 * databus-bpm-connector-service-design §10.4</a>）：
 * <ul>
 *   <li><b>no</b>：不回写</li>
 *   <li><b>all</b>：把 boResults[i].records 整体覆写到 {@code $.<rewrite.path>}</li>
 *   <li><b>boId</b>：转化为 add + addFields=["ID"]，对每条记录的 ID 字段写 {@code $.<path>[j].ID}</li>
 *   <li><b>add</b>：遍历 records 每条，对 addFields 中每个字段写 {@code $.<path>[j].<field>}</li>
 *   <li><b>exclude</b>：从 records 过滤掉 excludes 字段后整体覆写</li>
 *   <li><b>include</b>：从 records 只保留 includes 字段后整体覆写（includes 为空时退化为 all）</li>
 * </ul>
 *
 * @author databus
 */
@Data
public class BoCreateCfg {

    /** 连接实例 ID（必填） */
    private String connectionId;

    /** 创建方法：create（流程驱动，需 bindId）/ createDataBO（纯数据）。可选，默认 create */
    private String method;

    /** bindId，method=create 时必填 */
    private String bindId;

    /** 用户 ID（必填，构造 UserContext） */
    private String uid;

    /** BO 列表（必填），与 BPM 端响应 boResults 顺序一一对应 */
    private List<BoItemCfg> boList;

    /**
     * 单个 BO 项：BO 名称 + 数据空间源路径 + 回写策略。
     */
    @Data
    public static class BoItemCfg {

        /** BO 名称（必填） */
        private String boName;

        /** 数据空间源路径（必填），读取 {@code List<Map<String, Object>>} 作为 records */
        private String sourcePath;

        /** 回写策略配置（可选，缺省 strategy=no 不回写） */
        private RewriteCfg rewrite;
    }

    /**
     * 回写策略配置：strategy + 目标路径 + 策略相关字段。
     */
    @Data
    public static class RewriteCfg {

        /** 策略名：no / all / boId / add / exclude / include。必填 */
        private String strategy;

        /** 回写目标路径，如 {@code $.response.users}（strategy=no 时可省略） */
        private String path;

        /** add / boId 策略用的字段列表 */
        private List<String> addFields;

        /** exclude 策略用的过滤字段列表 */
        private List<String> excludes;

        /** include 策略用的保留字段列表（为空时退化为 all） */
        private List<String> includes;
    }
}
