package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 复制链路时的名称/编码建议值（弹窗预填，用户可改写）。
 *
 * @author databus
 */
@Data
public class CopySuggestionVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 建议名称：源名称 + "副本"
     */
    private String chainName;

    /**
     * 建议编码：源编码（剥离既有 _N 尾缀后）从 _2 起查库的首个未占用值
     */
    private String chainCode;

}
