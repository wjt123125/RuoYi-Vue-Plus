package org.dromara.databus.service;

import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;

import java.util.List;

/**
 * 物料市场 Service 接口（组件 / 链路市场）。
 * <p>
 * 三组能力：浏览（只读列表/分类/详情）、消费（安装＝fork / 卸载 / 我的已装）、
 * 供给（上架待审 / 下架 / 审核 / 删原件）。见设计正本 databus-material-market.md §十一。
 *
 * @author databus
 */
public interface IDatabusMarketService {

    /**
     * 市场物料列表（仅在线；类型/分类/关键字过滤 + 分页；回填当前用户是否已装）
     */
    PageResult<DatabusMarketItemVo> queryPageList(MarketQueryBo bo, PageQuery pageQuery, Long userId);

    /**
     * 分类列表（多级平表，含在线物料计数）
     */
    List<DatabusMarketCategoryVo> queryCategories();

    /**
     * 物料详情（在线或本人可见；回填是否已装）
     */
    DatabusMarketItemVo queryById(Long id, Long userId);

    /**
     * 安装＝fork（落快照 + 进我目录 + 记血缘）
     *
     * @param itemId     市场物料 id
     * @param directoryId 目标目录（null=未归组）
     * @param userId     当前用户 id
     * @return 安装记录视图
     */
    DatabusMarketInstallVo install(Long itemId, Long directoryId, Long userId);

    /**
     * 卸载（删我的本地副本 + 删血缘记录；不硬拦引用）
     */
    Boolean uninstall(Long resourceId, Long userId);

    /**
     * 我的已装列表（管理页数据源；源已删按本地副本兜底）
     */
    List<DatabusMarketInstallVo> queryInstalled(Long userId);

    /**
     * 提交上架（进待审；同源重复提交则更新并回到待审）
     */
    Boolean publish(MarketPublishBo bo, Long userId, String ownerCode);

    /**
     * 下架（在线 → 下线；仅本人）
     */
    Boolean unpublish(Long id, Long userId);

    /**
     * 审核（通过→上线 / 驳回→驳回态；禁自审，写审核人与时间）
     *
     * @param id         市场物料 id
     * @param bo         审核意见
     * @param reviewerId 审核人（治理者）id
     */
    Boolean review(Long id, MarketReviewBo bo, Long reviewerId);

    /**
     * 删市场原件（作者主权；与卸载分离）
     */
    Boolean deleteItem(Long id, Long userId);

    /**
     * 我的上架列表（作者视角治理视图，含各状态）
     */
    List<DatabusMarketItemVo> queryMine(Long userId);

    /**
     * 待审核列表（治理者视角，仅 PENDING）
     */
    List<DatabusMarketItemVo> queryPending();

}