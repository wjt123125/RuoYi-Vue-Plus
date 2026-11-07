package org.dromara.databus.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场分类合并业务对象：把源分类下的物料整体迁到目标分类，然后删源分类。
 * targetCategoryId 为空＝迁到「未分类」，效果等同清空后删除。
 *
 * @author databus
 */
@Data
public class MarketCategoryMergeBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 目标分类 id（null＝未分类）
     */
    private Long targetCategoryId;

}
