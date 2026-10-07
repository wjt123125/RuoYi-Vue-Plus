package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 物料业务域字典对象 databus_component_domain。
 *
 * <p>domain 的唯一正本：{@code @DatabusCmp} 注解与 CmpSchema 均无 domain 字段，
 * 域只能来自本表 + databus_component.domain 治理列。is_default='Y' 全表至多一行
 * （该行为 domain 为空件的兜底域）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_component_domain")
public class DatabusComponentDomain extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 业务域key（与 databus_component.domain 对齐）
     */
    private String domainKey;

    /**
     * 业务域中文名（台账树第二层目录标题）
     */
    private String domainName;

    /**
     * 目录色值（树目录色点/选择器分段圆点）
     */
    private String color;

    /**
     * 显示顺序（数值越小越靠前）
     */
    private Integer sort;

    /**
     * 是否兜底域（Y=databus_component.domain 为空的件归入此域；全表至多一行 Y）
     */
    private String isDefault;

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
