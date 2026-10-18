package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收藏业务对象（新增/取消收藏入参：资源类型 + 资源id；用户id由后端从登录态取）
 *
 * @author databus
 */
@Data
public class DatabusFavoriteBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 资源类型（chain / component）
     */
    @NotBlank(message = "资源类型不能为空")
    private String resourceType;

    /**
     * 资源id
     */
    @NotNull(message = "资源id不能为空")
    private Long resourceId;

}
