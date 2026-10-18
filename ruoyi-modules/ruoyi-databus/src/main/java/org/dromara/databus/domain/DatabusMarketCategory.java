package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 市场分类对象 databus_market_category
 * <p>
 * 物料市场的分类目录（多级平表，parent_id 自引用，0=根）；前端组树。
 * 大分类口径见设计正本 §四：组件沿用既有 taxonomy 分组，链路按业务域。
 *
 * @author databus
 */
@Data
@TableName("databus_market_category")
public class DatabusMarketCategory {

    /**
     * 主键id
     */
    @TableId(value = "id")
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
     * 排序（升序，值小在前，默认 0）
     */
    private Integer sort;

}