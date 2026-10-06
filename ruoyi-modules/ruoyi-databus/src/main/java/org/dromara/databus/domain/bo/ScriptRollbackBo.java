package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 脚本版本回滚入参：取目标版本源码重走保存管线，产生新版本行。
 *
 * @author databus
 */
@Data
public class ScriptRollbackBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 组件主键
     */
    @NotNull(message = "组件主键不能为空")
    private Long id;

    /**
     * 回滚目标版本号
     */
    @NotNull(message = "目标版本号不能为空")
    private Integer versionNo;

}
