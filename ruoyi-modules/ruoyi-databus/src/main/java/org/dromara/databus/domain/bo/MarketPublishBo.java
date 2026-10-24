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
     * 物料类型（COMPONENT / CHAIN / CONNECTOR——连接器 sourceId＝databus_connector_def 行 id，§15.7）
     */
    @NotBlank(message = "物料类型不能为空")
    private String resourceType;

    /**
     * 源资源 id（链路主键 / 组件 DB 行 id / 连接器 def 行 id）
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

    /**
     * 修改说明（P1 驳回-重提闭环：被驳回后重提时填写，说明本次修改了什么；审核者复核用）
     */
    @Size(max = 500, message = "修改说明长度不能超过{max}个字符")
    private String modifyNote;

}