package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusDirectory;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 统一目录视图对象 databus_directory
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusDirectory.class)
public class DatabusDirectoryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 父目录id（0=根目录）
     */
    private Long parentId;

    /**
     * 目录名称
     */
    private String directoryName;

    /**
     * 属主（null=团队共享树；PaaS 化后=用户id，每人一棵）
     */
    private Long ownerId;

    /**
     * 排序（升序，值小在前）
     */
    private Integer sort;

    /**
     * 备注
     */
    private String remark;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

}
