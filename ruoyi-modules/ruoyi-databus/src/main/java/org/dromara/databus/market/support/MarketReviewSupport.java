package org.dromara.databus.market.support;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.model.MarketClosure;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.STATUS_PENDING;
import static org.dromara.databus.constant.MarketConstants.STATUS_REJECTED;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 审核状态流转：通过与驳回，以及通过链路前的依赖上线校验。
 * <p>
 * 依赖清单与安装侧、提交上架侧、审核详情共用 {@link MarketClosureLoader} 的同一份，
 * 保证「审核人看到的状态」就是「点通过时会核对的状态」。
 * <p>
 * 事务边界留在 {@link MarketPublishService#review}，本类只承接实现。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class MarketReviewSupport {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 连接器运行层服务：连接器通过审核 = 首次启用
     */
    private final IDatabusConnectorService connectorService;

    /**
     * 查依赖清单：通过审核前查依赖时使用
     */
    private final MarketClosureLoader closureLoader;

    private final MarketItemLifecycleSupport lifecycleSupport;

    /**
     * 审核：通过就置为上线，驳回就置为驳回并记下理由。不许审自己上架的物料；
     * 通过一条链路前还要先查它的依赖是否都已上线。
     * 驳回连接器时连未审核的 def 和包一起撤回——驳回＝这份提交作废，不能继续占着全局唯一的 type。
     */
    public Boolean review(Long id, MarketReviewBo bo, Long reviewerId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!STATUS_PENDING.equals(item.getStatus())) {
            throw new ServiceException("仅待审物料可审核");
        }
        // 不许审自己的：审核人不能审核自己上架的物料（§七 7.1）
        if (Objects.equals(item.getOwnerId(), reviewerId)) {
            throw new ServiceException("不能审核自己上架的物料");
        }
        // 通过审核前先查依赖（decisions §十四-7）：approved 且是 CHAIN 时，依赖清单里全部依赖
        // （链和组件，owner_type=SYSTEM 的系统资源也算——全部都查，一个不放过）市场状态必须是 ONLINE。
        // 不满足就不给通过：主链上架以后，如果子物料被别人下架或驳回、变得查不到了，
        // 审核人这时候点通过就等于确认了一个「装出去必然坏」的物料（别人安装时会被依赖检查拒掉）。
        // 驳回、以及不是 CHAIN 的物料，都不做这个检查
        if (Boolean.TRUE.equals(bo.getApproved()) && TYPE_CHAIN.equals(item.getResourceType())) {
            validateClosureOnlineForReview(item);
        }
        // 连接器通过审核 = 首次启用：启用即审核（verified 勾选、提交人 ≠ 审批人）由 enable 内部校验，
        // 不再要求治理员先去别的页面手动启用。已启用的直接放行；已停用/已废弃是治理侧的运行态决定，
        // 审核不能绕过，必须先恢复启用。
        if (Boolean.TRUE.equals(bo.getApproved()) && TYPE_CONNECTOR.equals(item.getResourceType())) {
            DatabusConnectorDef def = connectorDefMapper.selectById(item.getSourceId());
            if (def == null) {
                throw new ServiceException("源连接器不存在或已卸载，无法通过审核");
            }
            if (ConnectorDefStatusEnum.PENDING.getCode().equals(def.getStatus())) {
                connectorService.enable(def.getType(), def.getVersion(), reviewerId, Boolean.TRUE);
            } else if (!ConnectorDefStatusEnum.ENABLED.getCode().equals(def.getStatus())) {
                throw new ServiceException("连接器 " + def.getType() + " 当前为 " + def.getStatus()
                    + "，不能通过审核；请先在物料治理里启用或恢复");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        DatabusMarketItem update = new DatabusMarketItem();
        update.setId(id);
        update.setUpdateTime(now);
        update.setReviewBy(reviewerId);
        update.setReviewTime(now);
        if (Boolean.TRUE.equals(bo.getApproved())) {
            update.setStatus(STATUS_ONLINE);
            update.setRemark("");
            // 通过后清空修改说明（这段说明是作者被驳回后重提时写的，物料上线后就没用了）
            update.setModifyNote("");
        } else {
            if (StringUtils.isBlank(bo.getReason())) {
                throw new ServiceException("驳回需填写理由");
            }
            update.setStatus(STATUS_REJECTED);
            update.setRemark(bo.getReason());
            // 记录留、包不留：物料行保留驳回理由给作者看，def 与 zip 撤回以释放 type/version
            if (TYPE_CONNECTOR.equals(item.getResourceType())) {
                lifecycleSupport.discardUnreviewedConnector(item, null);
            }
        }
        return marketItemMapper.updateById(update) > 0;
    }

    /**
     * 通过审核前先查依赖（decisions §十四-7）：一条链路要审核通过，它依赖清单里的
     * 全部依赖（链和组件都算，owner_type=SYSTEM 的系统资源也算，一个都不放过）
     * 市场状态必须都是 ONLINE。
     * <p>
     * 和安装侧的 {@link MarketInstallService#validateClosureOnMarket} 分工不同：
     * 那边管的是「现在装出去能不能用」，这边管的是「审核人点了通过，就等于确认了这个物料能装」。
     * 主链通过上线之后，它的子物料可能又被下架或驳回，这时候如果不拦住，
     * 市场里就会留一条别人一装就失败的物料。所以这里拒掉，让审核人先去处理依赖。
     * 链的依赖分「未上架」和「未过审」两种报错，组件的依赖多一种「已不存在」（组件行被删了）。
     *
     * @param item 正在审的这条链路的市场上架行（只有 approve=true 时才会进来）
     */
    private void validateClosureOnlineForReview(DatabusMarketItem item) {
        DatabusChain root = chainMapper.selectById(item.getSourceId());
        if (root == null) {
            throw new ServiceException("源链路不存在或已删除，无法审核通过");
        }
        MarketClosure closure = closureLoader.load(root.getChainCode());
        // 查链：主链就是正在审的这条（状态还是 PENDING），不能把它当自己的依赖，
        // 不然永远过不了。「排除主链自己」这一条只有审核这边有，
        // 安装侧的 validateClosureOnMarket 不排除
        for (DatabusChain dep : closure.chains().values()) {
            if (Objects.equals(dep.getId(), item.getSourceId())) {
                continue;
            }
            DatabusMarketItem depItem = closure.chainItems().get(dep.getId());
            if (depItem == null) {
                throw new ServiceException("依赖链路'" + dep.getChainName() + "'未上架市场，请先处理");
            }
            if (!STATUS_ONLINE.equals(depItem.getStatus())) {
                throw new ServiceException("依赖链路'" + dep.getChainName() + "'未过审（市场状态：" + depItem.getStatus() + "），请先处理");
            }
        }
        // 查组件：清单里所有链（包括主链）引用的组件合在一起查。组件行被删了的直接拒
        for (String code : closure.componentCodes()) {
            DatabusComponent component = closure.components().get(code);
            if (component == null) {
                throw new ServiceException("依赖组件'" + code + "'已不存在，请先处理");
            }
            DatabusMarketItem depItem = closure.componentItems().get(component.getId());
            if (depItem == null) {
                throw new ServiceException("依赖组件'" + component.getComponentName() + "'未上架市场，请先处理");
            }
            if (!STATUS_ONLINE.equals(depItem.getStatus())) {
                throw new ServiceException("依赖组件'" + component.getComponentName() + "'未过审（市场状态：" + depItem.getStatus() + "），请先处理");
            }
        }
    }

}
