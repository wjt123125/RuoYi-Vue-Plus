package org.dromara.databus.domain.bo;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 审核业务对象（通过 / 驳回；后期 AI 介入审核）
 *
 * @author databus
 */
@Data
public class MarketReviewBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 是否通过（true=上线，false=驳回）
     */
    @NotNull(message = "审核结论不能为空")
    private Boolean approved;

    /**
     * 驳回理由（approved=false 时）
     */
    @Size(max = 500, message = "驳回理由长度不能超过{max}个字符")
    private String reason;

}