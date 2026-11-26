package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.service.IDatabusConnectorService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Objects;

import static org.dromara.databus.constant.MarketConstants.STATUS_OFFLINE;
import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 上架行生命周期：下架、删除上架行，以及「撤回未审核的连接器提交」这条共用清扫。
 * <p>
 * 只被 {@link MarketPublishService}（下架/删除）与 {@link MarketReviewSupport}（驳回连接器）调用。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class MarketItemLifecycleSupport {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 安装台账：删上架行时判断连接器是否已被人装走（有安装记录就不随删除撤回 def）
     */
    private final DatabusInstallMapper installMapper;

    /**
     * 连接器运行层服务：撤回未审核提交时复用卸载的四步清扫
     */
    private final IDatabusConnectorService connectorService;

    /**
     * 下架：只有作者本人能下架，而且只能下架在线状态的物料。
     */
    public Boolean unpublish(Long id, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!Objects.equals(item.getOwnerId(), userId)) {
            throw new ServiceException("只能下架自己上架的物料");
        }
        if (!STATUS_ONLINE.equals(item.getStatus())) {
            throw new ServiceException("仅在线物料可下架");
        }
        DatabusMarketItem update = new DatabusMarketItem();
        update.setId(id);
        update.setStatus(STATUS_OFFLINE);
        update.setUpdateTime(LocalDateTime.now());
        return marketItemMapper.updateById(update) > 0;
    }

    /**
     * 删除上架行：只有作者本人能删，直接物理删（这张表没有逻辑删除）。
     * 别人已经装走的副本和安装记录都不动。
     */
    public Boolean deleteItem(Long id, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!Objects.equals(item.getOwnerId(), userId)) {
            throw new ServiceException("只能删除自己上架的物料");
        }
        // 连接器的 def 行与包是上传时建的，删上架行不会跟着消失；未审核的整次提交要连 def 和包一起撤回
        if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            discardUnreviewedConnector(item, userId);
        }
        // 已装副本不受影响：安装时存的是内容快照，不依赖市场这一行。
        // 安装记录行也不删，市场上架行查不到时由 queryInstalled 按「已下架」显示。
        return marketItemMapper.deleteById(id) > 0;
    }

    /**
     * 撤回未审核的连接器提交：def 仍是 PENDING，且没有其它市场行、也没人装过，
     * 就连同 def 行与磁盘包一起删（复用卸载的四步清扫）。
     * authorId 非空时要求 def 也属于该作者（作者删自己的提交）；审核驳回时传 null（治理动作，不校验作者）。
     * 已启用 / 停用 / 废弃的 def 是平台级资产，删上架行只下架展示，不动它。
     */
    public void discardUnreviewedConnector(DatabusMarketItem item, Long authorId) {
        DatabusConnectorDef def = connectorDefMapper.selectById(item.getSourceId());
        if (def == null
            || !ConnectorDefStatusEnum.PENDING.getCode().equals(def.getStatus())
            || (authorId != null && !Objects.equals(def.getCreateBy(), authorId))) {
            return;
        }
        Long siblingItems = marketItemMapper.selectCount(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getResourceType, TYPE_CONNECTOR)
            .eq(DatabusMarketItem::getSourceId, item.getSourceId())
            .ne(DatabusMarketItem::getId, item.getId()));
        Long installs = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getMarketItemId, item.getId()));
        if ((siblingItems != null && siblingItems > 0) || (installs != null && installs > 0)) {
            return;
        }
        connectorService.uninstall(def.getType(), def.getVersion(), true);
    }

}
