package org.dromara.databus.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.MarketInstallMoveBo;
import org.dromara.databus.domain.bo.MarketPublishBatchBo;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.domain.vo.DatabusMarketPreviewVo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.market.MarketInstallService;
import org.dromara.databus.market.MarketPublishService;
import org.dromara.databus.market.MarketQueryService;
import org.dromara.databus.service.IDatabusMarketService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 物料市场 Service 实现（设计文档 databus-material-market.md §五~§十一）。
 * <p>
 * 本类只负责转手：控制器只认 {@link IDatabusMarketService}，真正的逻辑按职责分在三个类里——
 * <ul>
 *   <li>{@link MarketQueryService}：查询，只读（列表 / 分类 / 详情 / 我的上架 / 待审核 / 安装前看实现）；</li>
 *   <li>{@link MarketInstallService}：安装和卸载（链路＝复制一份副本 / 组件＝大家共用同一个 / 连接器＝启用）；</li>
 *   <li>{@link MarketPublishService}：上架和审核（提交审核 / 顺带补审核单 / 下架 / 审核 / 删除 / 审核详情）。</li>
 * </ul>
 * 转手的时候不加事务、也不加校验：写操作的事务就打在三个类各自的方法上
 * （{@code publishBatch} 里面直接调 {@code publish}，同一个类内部调用不走 Spring 代理，
 * 所以这两个方法必须都放在 {@link MarketPublishService} 里，
 * 内层的事务才不起作用、整批由外层一个事务管着，才能做到「要么全成，要么全不成」）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusMarketServiceImpl implements IDatabusMarketService {

    private final MarketQueryService queryService;

    private final MarketInstallService installService;

    private final MarketPublishService publishService;

    @Override
    public PageResult<DatabusMarketItemVo> queryPageList(MarketQueryBo bo, PageQuery pageQuery, Long userId) {
        return queryService.queryPageList(bo, pageQuery, userId);
    }

    @Override
    public List<DatabusMarketCategoryVo> queryCategories() {
        return queryService.queryCategories();
    }

    @Override
    public DatabusMarketItemVo queryById(Long id, Long userId) {
        return queryService.queryById(id, userId);
    }

    @Override
    public DatabusMarketInstallVo install(Long itemId, Long directoryId, Long userId) {
        return installService.install(itemId, directoryId, userId);
    }

    @Override
    public Boolean uninstall(Long resourceId, Long userId) {
        return installService.uninstall(resourceId, userId);
    }

    @Override
    public List<DatabusMarketInstallVo> queryInstalled(Long userId) {
        return installService.queryInstalled(userId);
    }

    @Override
    public Boolean moveInstall(MarketInstallMoveBo bo, Long userId) {
        return installService.moveInstall(bo, userId);
    }

    @Override
    public Boolean publish(MarketPublishBo bo, Long userId, String ownerCode) {
        return publishService.publish(bo, userId, ownerCode);
    }

    @Override
    public Boolean publishBatch(MarketPublishBatchBo bo, Long userId, String ownerCode) {
        return publishService.publishBatch(bo, userId, ownerCode);
    }

    @Override
    public Boolean unpublish(Long id, Long userId) {
        return publishService.unpublish(id, userId);
    }

    @Override
    public Boolean review(Long id, MarketReviewBo bo, Long reviewerId) {
        return publishService.review(id, bo, reviewerId);
    }

    @Override
    public Boolean deleteItem(Long id, Long userId) {
        return publishService.deleteItem(id, userId);
    }

    @Override
    public List<DatabusMarketItemVo> queryMine(Long userId) {
        return queryService.queryMine(userId);
    }

    @Override
    public List<DatabusMarketItemVo> queryPending(Long userId) {
        return queryService.queryPending(userId);
    }

    @Override
    public DatabusMarketReviewDetailVo reviewDetail(Long id) {
        return publishService.reviewDetail(id);
    }

    @Override
    public DatabusMarketPreviewVo preview(Long id) {
        return queryService.preview(id);
    }

}
