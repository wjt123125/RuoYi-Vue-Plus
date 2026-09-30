package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 复制链路业务对象：副本的名称与编码在「创建那一刻」由用户确认。
 * <p>
 * chainCode 是链路终身身份，副本即一条全新链路，故编码在此定型、之后不可改；
 * 提交时走与新增链路同款的唯一性校验。
 *
 * @author databus
 */
@Data
public class ChainCopyBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 副本链路名称
     */
    @NotBlank(message = "链路名称不能为空")
    @Size(max = 100, message = "链路名称长度不能超过{max}个字符")
    private String chainName;

    /**
     * 副本链路编码（终身身份，创建后不可改）
     */
    @NotBlank(message = "链路编码不能为空")
    @Size(max = 100, message = "链路编码长度不能超过{max}个字符")
    private String chainCode;

}
