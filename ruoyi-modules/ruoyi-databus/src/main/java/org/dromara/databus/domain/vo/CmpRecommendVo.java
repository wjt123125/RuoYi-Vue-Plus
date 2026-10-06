package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 组件推荐项：只给组件类型与融合后的分数，排序由后端完成。
 *
 * <p>前端把它当「加权覆盖层」：本列表里出现的类型用这里的分，没出现的类型
 * 继续用前端本地规则分，保证后端不可用或新组件未建档时弹窗仍可完整工作。
 *
 * @author databus
 */
@Data
public class CmpRecommendVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 组件 def.type
     */
    private String type;

    /**
     * 融合后分数 0-100（种子基线 + 真账对数boost）
     */
    private Integer score;

    public CmpRecommendVo() {
    }

    public CmpRecommendVo(String type, Integer score) {
        this.type = type;
        this.score = score;
    }

}
