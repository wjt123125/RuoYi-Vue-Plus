package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 连接器运行层状态（databus_connector_def.status）
 * <ul>
 *   <li>PENDING 待审核：上传后的初始态，不可执行</li>
 *   <li>ENABLED 启用：可执行</li>
 *   <li>DISABLED 停用：挡新建连接，存量保留</li>
 *   <li>DEPRECATED 废弃：终态，不可逆</li>
 * </ul>
 * 上传 → PENDING →（他人审核）→ ENABLED ⇄ DISABLED → DEPRECATED。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum ConnectorDefStatusEnum {

    /**
     * 待审核
     */
    PENDING("PENDING", "待审核"),

    /**
     * 启用
     */
    ENABLED("ENABLED", "启用"),

    /**
     * 停用
     */
    DISABLED("DISABLED", "停用"),

    /**
     * 废弃
     */
    DEPRECATED("DEPRECATED", "废弃");

    /**
     * 状态编码。
     */
    private final String code;

    /**
     * 状态说明。
     */
    private final String info;

}