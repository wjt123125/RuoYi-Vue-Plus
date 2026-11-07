package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 市场分类新增 / 改名业务对象（治理动作）。
 * <p>
 * resourceType 只在新增时生效：分类一旦建好，适用类型不再改动，
 * 避免分类类型与已挂物料类型不一致产生孤儿归属。
 *
 * @author databus
 */
@Data
public class MarketCategoryBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分类名称
     */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 100, message = "分类名称长度不能超过{max}个字符")
    private String name;

    /**
     * 适用物料类型（COMPONENT / CHAIN / CONNECTOR；空串＝通用）
     */
    private String resourceType;

    /**
     * 排序（升序，值小在前，默认 0）
     */
    private Integer sort;

    /**
     * 推荐标签（发布页标签候选池的分类侧补充值；与该分类下物料的 tags 合并去重）
     */
    private List<String> defaultTags;

}
