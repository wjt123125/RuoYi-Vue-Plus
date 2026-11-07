package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;

/**
 * 市场分类对象 databus_market_category
 * <p>
 * 物料市场的分类目录（多级平表，parent_id 自引用，0=根）；前端组树。
 * 大分类口径见设计正本 §四：组件沿用既有 taxonomy 分组，链路按业务域。
 *
 * @author databus
 */
@Data
@TableName(value = "databus_market_category", autoResultMap = true)
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

    /**
     * 适用物料类型（COMPONENT / CHAIN / CONNECTOR；空串＝通用）
     */
    private String resourceType;

    /**
     * 推荐标签（default_tags 列 JSON 数组自动互转）。
     * 发布页的标签候选池＝该分类下已有物料的 tags ∪ 本列，空分类（尤其连接器）也有推荐值。
     */
    @TableField(value = "default_tags", typeHandler = JacksonTypeHandler.class)
    private List<String> defaultTags;
}
