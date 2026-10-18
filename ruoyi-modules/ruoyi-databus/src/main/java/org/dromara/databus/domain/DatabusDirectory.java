package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 统一目录对象 databus_directory
 * <p>
 * 工作区统一资源树的组织层：parent_id 自引用支持多级目录；
 * 叶子混装链路（databus_chain.directory_id）与自建组件（databus_component.directory_id），
 * null/0 = 未归组，由前端树虚拟节点兜底。
 * 取代原 databus_chain_directory 表（2026-10-17 工作区融合，见 workbench-unification.md §3）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "databus_directory")
public class DatabusDirectory extends BaseEntity {

    /**
     * 主键id
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 父目录id（0=根目录，自引用多级树）
     */
    private Long parentId;

    /**
     * 目录名称（同级唯一）
     */
    private String directoryName;

    /**
     * 属主（null=团队共享树；PaaS 化后=用户id，每人一棵）
     */
    private Long ownerId;

    /**
     * 排序（升序，值小在前，默认 0）
     */
    private Integer sort;

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
