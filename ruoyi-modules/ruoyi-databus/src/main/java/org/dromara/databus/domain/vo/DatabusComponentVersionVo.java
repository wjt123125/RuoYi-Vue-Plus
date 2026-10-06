package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 脚本组件版本历史视图对象
 *
 * @author databus
 */
@Data
public class DatabusComponentVersionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键id
     */
    private Long id;

    /**
     * 组件主键
     */
    private Long componentId;

    /**
     * 版本号
     */
    private Integer versionNo;

    /**
     * 脚本语言
     */
    private String scriptLang;

    /**
     * 脚本正文
     */
    private String scriptBody;

    /**
     * 版本备注
     */
    private String remark;

    /**
     * 创建者
     */
    private Long createBy;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
