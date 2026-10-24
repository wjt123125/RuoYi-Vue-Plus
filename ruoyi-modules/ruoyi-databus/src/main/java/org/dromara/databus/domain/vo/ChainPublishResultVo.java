package org.dromara.databus.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 链路发布结果视图对象（decisions §十四-6 发布侧引导上架）。
 * <p>
 * 发布成功响应携带闭包内「未上架市场」的引导信息：自己的（子链＋组件）进引导清单，
 * 供前端弹窗「上架会将子链和组件一并上架」→ 确认 → 批量提审（POST /databus/market/publish/batch，
 * 逐项映射为 MarketPublishBo）；他人的链未上架在发布侧直接拒绝（无权代提，不在本 VO 出现）；
 * 他人未上架的组件不阻塞发布，仅列名提示（组件运行不依赖市场身份）。
 *
 * @author databus
 * @since 2026/10/23
 */
@Data
public class ChainPublishResultVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 闭包内自己的未上架物料引导清单（主链自身在无市场行时进第一项＋子链＋组件；
     * 2026-10-24 闭包联审起主链一并引导，避免「依赖在审、主链没单」的半截状态）
     */
    private List<GuideItemVo> guideItems;

    /**
     * 闭包内他人未上架的组件名称清单（仅提示不阻塞，decisions §十四-6「不硬校验」）
     */
    private List<String> otherUnlistedComponents;

    /**
     * 引导清单条目：一项待提审的闭包物料（前端确认后逐项构造 MarketPublishBo 批量提审）。
     */
    @Data
    public static class GuideItemVo implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * 物料类型（CHAIN / COMPONENT）
         */
        private String resourceType;

        /**
         * 源资源 id（链路主键 / 组件行 id）
         */
        private Long sourceId;

        /**
         * 名称（弹窗展示）
         */
        private String name;
    }

}
