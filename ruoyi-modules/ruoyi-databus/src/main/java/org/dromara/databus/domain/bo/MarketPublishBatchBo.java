package org.dromara.databus.domain.bo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 批量提审业务对象（decisions §十四-6 发布引导链路：发布响应带引导清单 → 弹窗确认 → 一次性提审）。
 * <p>
 * 逐项复用 {@link MarketPublishBo}（嵌套校验）；userId/ownerCode 由后端从登录态取。
 *
 * @author databus
 * @since 2026/10/23
 */
@Data
public class MarketPublishBatchBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 上架清单（链＋组件混排，来自发布响应的引导清单）
     */
    @NotEmpty(message = "上架清单不能为空")
    @Valid
    private List<MarketPublishBo> items;

}
