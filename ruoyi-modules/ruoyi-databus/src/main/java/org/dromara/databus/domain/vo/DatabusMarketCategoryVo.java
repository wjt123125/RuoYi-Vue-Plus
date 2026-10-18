package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 市场分类视图对象（多级平表，前端组树）
 *
 * @author databus
 */
@Data
public class DatabusMarketCategoryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 父分类id（0=根）
     */
    private Long parentId;

    /**
     * 分类名称
     */
    private String name;

    /**
     * 排序（升序）
     */
    private Integer sort;

    /**
     * 分类下物料数（在线物料统计，chips 用）
     */
    private Long itemCount;

}