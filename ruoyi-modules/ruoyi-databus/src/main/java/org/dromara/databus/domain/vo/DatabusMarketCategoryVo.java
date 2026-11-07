package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

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
     * 适用物料类型（COMPONENT / CHAIN / CONNECTOR；空串＝通用）
     */
    private String resourceType;

    /**
     * 推荐标签（发布页标签候选池的分类侧补充值）
     */
    private List<String> defaultTags;

    /**
     * 分类下物料数（不限状态：治理侧据此判断能否直接删 / 只能合并）
     */
    private Long itemCount;

}