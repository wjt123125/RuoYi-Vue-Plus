package org.dromara.databus.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import org.dromara.databus.domain.DatabusChainDirectory;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 链路目录视图对象 databus_chain_directory
 *
 * @author databus
 */
@Data
@AutoMapper(target = DatabusChainDirectory.class)
public class DatabusChainDirectoryVo implements Serializable {

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
