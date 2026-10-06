package org.dromara.databus.domain.vo;

/**
 * 物料来源（/options 同码合流新秩序，2026-10-06 脚本宿主立法反转）：
 * <ol>
 *   <li>同码启用且 script_body 非空＝库存脚本件，全量取 DB（{@link #CUSTOM}）；</li>
 *   <li>同码启用但无脚本体＝治理覆盖行：治理字段取 DB、schema 契约取内置注解（{@link #OVERLAY}）；</li>
 *   <li>无 DB 行＝纯内置注解件（{@link #SYSTEM}）。</li>
 * </ol>
 *
 * @author databus
 */
public enum ComponentSource {

    /**
     * 内置物料：@DatabusCmp 注解 + 启动扫描注册（无 DB 行时出现）。
     */
    SYSTEM,

    /**
     * 库存/自定义物料：databus_component 表启用行。有 script_body 时为库存脚本件
     *（运行时由脚本宿主注册，DB 全量正本）；无 script_body 且无同码内置件时为纯自定义件。
     */
    CUSTOM,

    /**
     * 治理覆盖行：DB 行与内置件同码但无脚本体——治理字段（名称/图标/分组/标签/废弃）
     * 以 DB 为正本，nodeType/editor/paramSchema 等契约仍取内置注解。
     */
    OVERLAY
}
