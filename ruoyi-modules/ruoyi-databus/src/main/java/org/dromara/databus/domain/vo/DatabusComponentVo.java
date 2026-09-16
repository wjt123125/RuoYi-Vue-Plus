package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusComponent;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 组件元信息视图对象 databus_component
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusComponent.class)
public class DatabusComponentVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 组件编码
     */
    private String componentCode;

    /**
     * 组件名称
     */
    private String componentName;

    /**
     * 组件分类
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
     * 参数 Schema（JSON Schema 格式）
     */
    private String paramSchema;

    /**
     * 输入 Schema
     */
    private String inputSchema;

    /**
     * 输出 Schema
     */
    private String outputSchema;

    /**
     * 状态（0启用 1停用）
     */
    private String status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
