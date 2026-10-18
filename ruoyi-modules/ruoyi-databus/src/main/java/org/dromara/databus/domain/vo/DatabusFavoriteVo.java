package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收藏条目视图对象（工作区统一资源树「我的收藏」section 平铺渲染用）
 * <p>
 * 由收藏行回查资源名/编码/图标：chain 回查链路名与编码，component 回查组件名与编码、图标。
 *
 * @author databus
 */
@Data
public class DatabusFavoriteVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型（chain / component）
     */
    private String resourceType;

    /**
     * 资源id（chain=链路主键，component=组件主键）
     */
    private Long resourceId;

    /**
     * 资源名称（链路名或组件名；资源已删时为空）
     */
    private String resourceName;

    /**
     * 资源编码（链路编码或组件编码；资源已删时为空）
     */
    private String resourceCode;

    /**
     * 资源图标（组件图标名；链路为空或前端按类型兜底）
     */
    private String icon;

}
