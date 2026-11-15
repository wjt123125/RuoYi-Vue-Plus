package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 脚本草稿采纳入参：人在审阅页确认后点「采纳」，把草稿转启用并走一次脚本保存管线。
 *
 * @author databus
 */
@Data
public class ScriptAdoptBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 草稿组件主键
     */
    @NotNull(message = "草稿组件主键不能为空")
    private Long id;

}