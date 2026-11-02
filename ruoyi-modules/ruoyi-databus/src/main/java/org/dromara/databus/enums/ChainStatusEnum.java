package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据总线链路状态（databus_chain.status）
 * <p>
 * 轻量单表模式三态流转：
 * <ul>
 *   <li>DRAFT 草稿：可编辑，不可执行</li>
 *   <li>ENABLED 已发布：可执行，不可编辑（需重新发布）</li>
 *   <li>DISABLED 已下线：不可执行，可重新发布（定义保留不物理删除）</li>
 * </ul>
 * 流转：发布 DRAFT/DISABLED → ENABLED + version+1；下线 ENABLED → DISABLED。
 * <p>
 * 状态码与 {@link ComponentStatusEnum}、{@link ConnectorDefStatusEnum} 共用同一套资产词汇：
 * DRAFT / ENABLED / DISABLED（连接器另有 PENDING、DEPRECATED）。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum ChainStatusEnum {

    /**
     * 草稿
     */
    DRAFT("DRAFT", "草稿"),

    /**
     * 已发布
     */
    ENABLED("ENABLED", "已发布"),

    /**
     * 已下线
     */
    DISABLED("DISABLED", "已下线");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}