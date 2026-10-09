package org.dromara.databus.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 链路目录对象 databus_chain_directory
 * <p>
 * 链路工作台资源树的组织层：parent_id 自引用支持多级目录；
 * 链表 directory_id 可空外键挂接（null/0 = 未归组链，由前端树虚拟节点兜底）。
 *
 * @author databus
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "databus_chain_directory")
public class DatabusChainDirectory extends BaseEntity {

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
