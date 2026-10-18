package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 上架提交业务对象（供给侧，进待审）
 * <p>
 * ownerId/ownerCode 由后端从登录态取；name/icon/color 源自源资源，不由此表单传入。
 *
 * @author databus
 */
@Data
public class MarketPublishBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 物料类型（COMPONENT / CHAIN）
     */
    @NotBlank(message = "物料类型不能为空")
    private String resourceType;

    /**
     * 源资源 id（链路主键 / 组件 DB 行 id）
     */
    @NotNull(message = "源资源id不能为空")
    private Long sourceId;

    /**
     * 简介
     */
    @Size(max = 500, message = "简介长度不能超过{max}个字符")
    private String summary;

    /**
     * 分类id
     */
    private Long categoryId;

    /**
     * 标签列表
     */
    private List<String> tags;

}