package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场列表查询业务对象（类型 / 分类 / 关键字；分页走 PageQuery）
 *
 * @author databus
 */
@Data
public class MarketQueryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 物料类型（COMPONENT / CHAIN；空=全部）
     */
    private String resourceType;

    /**
     * 分类id（空=全部）
     */
    private Long categoryId;

    /**
     * 关键字（名称 / 编码 / 标签，与资源树同一过滤口径）
     */
    private String keyword;

    /**
     * 作者登录名（点卡片 @作者 / 详情页作者筛选其作品；空=全部）
     */
    private String ownerCode;

}