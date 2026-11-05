package org.dromara.databus.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 物料归属类型（databus_chain / databus_component / databus_connector_def.owner_type）。
 * <p>
 * 上架权限判据（MarketPublishService.publish §七 7.1）：
 * <ul>
 *   <li>SYSTEM 系统资源：平台内置/种子数据，仅带 databus:market:review 权限的治理角色可上架；</li>
 *   <li>USER 用户资源：用户创建的资源，仅 create_by 指向的作者本人可上架。</li>
 * </ul>
 * 系统/用户归属由本字段显式表达，不借用审计字段 create_by。
 *
 * @author databus
 */
@Getter
@AllArgsConstructor
public enum OwnerTypeEnum {

    /**
     * 系统资源（平台内置/种子数据，仅治理角色可上架）
     */
    SYSTEM("SYSTEM", "系统资源"),

    /**
     * 用户资源（用户创建，仅作者本人可上架）
     */
    USER("USER", "用户资源");

    /**
     * 归属类型编码。
     */
    private final String code;

    /**
     * 归属类型说明。
     */
    private final String info;

}
