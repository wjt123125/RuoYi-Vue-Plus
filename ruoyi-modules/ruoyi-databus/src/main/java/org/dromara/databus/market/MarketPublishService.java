package org.dromara.databus.market;

import lombok.RequiredArgsConstructor;
import org.dromara.databus.domain.bo.MarketPublishBatchBo;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 物料市场——上架与审核（设计文档 databus-material-market.md §七、§十，decisions §十四-6/7/8）。
 * <p>
 * 本类只保留对外入口与事务边界，实现分四处：
 * 提交上架（含顺带补依赖审核单）在 {@link MarketPublishSubmitSupport}，
 * 审核在 {@link MarketReviewSupport}，审核详情装配在 {@link MarketReviewDetailSupport}，
 * 下架/删除/撤回未审核连接器在 {@link MarketItemLifecycleSupport}。
 * <p>
 * 两条跨资源的规则要点：
 * <ul>
 *   <li>提交链路时顺带把同一作者的依赖补成待审行，审核人一条主链单就能审完整棵依赖树；</li>
 *   <li>链路审核通过前要求依赖清单全部已上线（与安装侧同口径），避免通过一个装出去必然坏的物料。</li>
 * </ul>
 * 两条规则用的都是 {@link org.dromara.databus.market.support.MarketClosureLoader} 查出来的同一份清单。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketPublishService {

    private final MarketPublishSubmitSupport submitSupport;

    private final MarketReviewSupport reviewSupport;

    private final MarketReviewDetailSupport reviewDetailSupport;

    private final MarketItemLifecycleSupport lifecycleSupport;

    /**
     * 连接器运行层服务：连接器包上架时先落包建 def，再建市场物料
     */
    private final IDatabusConnectorService connectorService;

    /**
     * 提交上架：新建一条待审物料行；如果这个资源以前上架过，就把原来那行的展示信息改掉、
     * 状态回到待审。提交链路时还会顺带给同一个人的依赖补审核单。
     *
     * @return 物料行 id（新建或复用的同源行），供调用方定位「刚提交的是哪一条」
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publish(MarketPublishBo bo, Long userId, String ownerCode) {
        return submitSupport.publish(bo, userId, ownerCode);
    }

    /**
     * 连接器包上架：上传即提交，上传不再是独立动作。
     * <p>
     * 落包 + 建/更新 def（PENDING）+ 建/重置市场物料（PENDING）在同一个事务里完成：
     * 提交人只面对「上架」这一件事，失败时 DB 一起回滚；磁盘上的包目录是事务外产物，
     * 失败时可能留下一个无 def 行的目录，下次同 type/version 重新上传会覆盖它，不影响正确性。
     * <p>
     * def 的 create_by 由 {@link IDatabusConnectorService#upload} 写成当前用户，
     * 因此后面的归属校验（只能上架自己的资源）天然通过，也保证「提交人 ≠ 审批人」成立。
     * @param ownerCode 当前用户账号
     * @return 新物料行 id（前端据此跳到「我的上架」并高亮该行）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long publishConnector(MultipartFile file, MarketPublishBo bo, Long userId, String ownerCode) {
        DatabusConnectorDefVo def = connectorService.upload(file, userId);
        bo.setResourceType(TYPE_CONNECTOR);
        bo.setSourceId(def.getId());
        return publish(bo, userId, ownerCode);
    }

    /**
     * 批量提交审核：一条条调用单条的 publish，权限检查和重复提交的处理都复用同一套。
     * 整批要么全成，要么全不成。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean publishBatch(MarketPublishBatchBo bo, Long userId, String ownerCode) {
        // 批量提交审核（decisions §十四-6，发布引导弹窗确认后的写库动作）。
        // 中途任何一条被拒（比如是别人的资源、编码含系统保留分隔符），前面已经处理过的
        // 都随本事务一起回滚，用户在弹窗里重选后整批重新提交。
        // 注意：这里 this.publish() 是同一个类内部直接调用，不走 Spring 代理，
        // 所以 publish 上的 @Transactional 不生效，事务完全由本方法这一层管着——
        // 这样才有「整批一起回滚」的效果。因此这两个方法必须待在同一个类里，拆成两个类就没了这个效果
        for (MarketPublishBo item : bo.getItems()) {
            publish(item, userId, ownerCode);
        }
        return true;
    }

    /**
     * 下架：只有作者本人能下架，而且只能下架在线状态的物料。
     */
    public Boolean unpublish(Long id, Long userId) {
        return lifecycleSupport.unpublish(id, userId);
    }

    /**
     * 审核：通过就置为上线，驳回就置为驳回并记下理由。不许审自己上架的物料；
     * 通过一条链路前还要先查它的依赖是否都已上线。
     * 驳回连接器时连未审核的 def 和包一起撤回——驳回＝这份提交作废，不能继续占着全局唯一的 type。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean review(Long id, MarketReviewBo bo, Long reviewerId) {
        return reviewSupport.review(id, bo, reviewerId);
    }

    /**
     * 删除上架行：只有作者本人能删，直接物理删（这张表没有逻辑删除）。
     * 别人已经装走的副本和安装记录都不动。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteItem(Long id, Long userId) {
        return lifecycleSupport.deleteItem(id, userId);
    }

    /**
     * 审核详情：物料本身 ＋ 它依赖了哪些东西及各自的市场状态 ＋ 内容预览。
     */
    public DatabusMarketReviewDetailVo reviewDetail(Long id) {
        return reviewDetailSupport.reviewDetail(id);
    }

}
