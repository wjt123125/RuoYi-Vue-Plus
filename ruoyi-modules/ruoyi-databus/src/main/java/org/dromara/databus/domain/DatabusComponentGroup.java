package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 物料面板分组字典对象 databus_component_group。
 *
 * <p>group 的正本在 jar 注解 {@code @DatabusCmp.group()}，本表只是「合法值白名单 + 展示元数据」；
 * group_key 必须与前端 STRUCTURE_DEFS 静态件的 group 字面量逐字一致，否则静态件落不进任何组。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("databus_component_group")
public class DatabusComponentGroup extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 分组key（flow/sequence/branch/loop/other/subflow/business，与注解 group() 及
     * databus_component.group_name 对齐）
     */
    private String groupKey;

    /**
     * 分组中文标题（编辑器面板与台账树目录名）
     */
    private String groupName;

    /**
     * 分组色值（面板标题圆点/树目录色点，如 #409eff）
     */
    private String color;

    /**
     * 显示顺序（数值越小越靠前）
     */
    private Integer sort;

    /**
     * 是否结构组（Y=前端 STRUCTURE_DEFS 硬编码引用，不可删；N=可自由增删）
     */
    private String builtin;

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
