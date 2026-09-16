package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 组件元信息对象 databus_component
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_component")
public class DatabusComponent extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 组件编码，与 @LiteflowComponent value 一一对应
     */
    private String componentCode;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 组件分类（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM）
     */
    private String category;

    /**
     * 组件图标
     */
    private String icon;

    /**
     * 组件描述
     */
    private String description;

    /**
     * 参数 Schema（JSON Schema 格式，供配置面板动态渲染）
     */
    private String paramSchema;

    /**
     * 输入 Schema（连线校验用）
     */
    private String inputSchema;

    /**
     * 输出 Schema（连线校验用）
     */
    private String outputSchema;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 删除标志（0存在 1删除）
     */
    @TableLogic
    private String delFlag;

    /**
     * 备注（BaseEntity 不含 remark，表列自持）
     */
    private String remark;

}
