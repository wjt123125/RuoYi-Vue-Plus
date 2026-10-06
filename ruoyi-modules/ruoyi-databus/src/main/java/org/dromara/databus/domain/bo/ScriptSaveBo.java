package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 脚本保存入参（独立于治理编辑 Bo，端点单独鉴权 databus:component:script:edit）。
 *
 * @author databus
 */
@Data
public class ScriptSaveBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 组件主键（脚本只能保存到已存在的组件行；新建组件先走治理新增）
     */
    @NotNull(message = "组件主键不能为空")
    private Long id;

    /**
     * 脚本语言（缺省 java）
     */
    @Size(max = 16, message = "脚本语言长度不能超过{max}个字符")
    private String scriptLang;

    /**
     * 脚本正文（完整 Java 类源码）
     */
    @NotBlank(message = "脚本正文不能为空")
    private String scriptBody;

}
