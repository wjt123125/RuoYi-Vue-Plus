package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 安装/血缘视图对象（管理页「我的已装」行列表用）
 * <p>
 * databus_install 行 + 展示字段：name/summary/icon/color/enabled 优先取本地副本（我方物料的
 * 当前态），resourceType 取市场物料（源已删时按本地副本落在哪张表推断）。
 *
 * @author databus
 */
@Data
public class DatabusMarketInstallVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 安装者 id
     */
    private Long ownerId;

    /**
     * 市场物料 id
     */
    private Long marketItemId;

    /**
     * 本地副本 id
     */
    private Long localResourceId;

    /**
     * 安装模式（FORK / FAVORITE）
     */
    private String mode;

    /**
     * 落快照时的逻辑源码指纹
     */
    private String contentHash;

    /**
     * 安装时间
     */
    private LocalDateTime createTime;

    /**
     * 物料类型（COMPONENT / CHAIN）
     */
    private String resourceType;

    /**
     * 名称（本地副本优先）
     */
    private String name;

    /**
     * 简介（市场物料优先，回退本地备注/描述）
     */
    private String summary;

    /**
     * 图标（本地副本优先）
     */
    private String icon;

    /**
     * 色值（本地副本优先）
     */
    private String color;

    /**
     * 本地副本启停态（组件停用=true；链路无开关，取已发布=true）
     */
    private Boolean enabled;

}