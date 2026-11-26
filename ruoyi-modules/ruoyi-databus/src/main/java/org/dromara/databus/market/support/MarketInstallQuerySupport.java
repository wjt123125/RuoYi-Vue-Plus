package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusExecution;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusExecutionMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.model.InstallOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dromara.databus.constant.MarketConstants.DRAFT_VERSION;
import static org.dromara.databus.constant.MarketConstants.DISPLAY_SEPARATOR;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 安装侧的查询与展示：已装列表（关联物料行/本地资源补展示字段）、安装结果的 VO 组装，
 * 以及安装/卸载后三个缓存计数（install / ref / forkPublish）的重算。
 * <p>
 * 只被 MarketInstallService 调用；计数刷新写库，查询只读，均不反向依赖 Service。
 */
@RequiredArgsConstructor
@Component
public class MarketInstallQuerySupport {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 执行记录：已装管理列表取链路最近一次执行状态（运行健康圆点）
     */
    private final DatabusExecutionMapper executionMapper;

    private final MarketInstallCodeSupport codeSupport;

    /**
     * 我装过的东西：把安装记录和物料行、本地资源关联起来，补上名称、图标、启用状态这些展示字段。
     */
    public List<DatabusMarketInstallVo> queryInstalled(Long userId) {
        List<DatabusInstall> records = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .orderByDesc(DatabusInstall::getCreateTime)
            .orderByDesc(DatabusInstall::getId));
        if (records.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> itemIds = records.stream()
            .map(DatabusInstall::getMarketItemId).filter(Objects::nonNull).distinct().toList();
        Map<Long, DatabusMarketItem> itemMap = itemIds.isEmpty() ? Map.of()
            : marketItemMapper.selectByIds(itemIds).stream()
                .collect(Collectors.toMap(DatabusMarketItem::getId, item -> item, (a, b) -> a));

        Set<Long> localIds = records.stream()
            .map(DatabusInstall::getLocalResourceId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, DatabusChain> chainMap = localIds.isEmpty() ? Map.of()
            : chainMapper.selectByIds(localIds).stream()
                .collect(Collectors.toMap(DatabusChain::getId, chain -> chain, (a, b) -> a));
        Map<Long, DatabusComponent> componentMap = localIds.isEmpty() ? Map.of()
            : componentMapper.selectByIds(localIds).stream()
                .collect(Collectors.toMap(DatabusComponent::getId, component -> component, (a, b) -> a));
        Map<Long, DatabusConnectorDef> defMap = localIds.isEmpty() ? Map.of()
            : connectorDefMapper.selectByIds(localIds).stream()
                .collect(Collectors.toMap(DatabusConnectorDef::getId, def -> def, (a, b) -> a));
        // 链路运行健康：一次批量取每条链的最近一次执行状态（无记录不放进 map，前端按「未跑过」处理）
        Map<Long, String> chainHealthMap = new HashMap<>();
        if (!chainMap.isEmpty()) {
            for (DatabusExecution execution : executionMapper.selectLatestByChainIds(chainMap.keySet())) {
                chainHealthMap.put(execution.getChainId(), execution.getStatus());
            }
        }

        List<DatabusMarketInstallVo> result = new ArrayList<>(records.size());
        for (DatabusInstall record : records) {
            // 类型读记录上的判别列；下面三张表只是为了取展示字段，不再参与类型判定
            String resourceType = record.getLocalResourceType();
            DatabusMarketItem item = itemMap.get(record.getMarketItemId());
            DatabusChain chain = chainMap.get(record.getLocalResourceId());
            DatabusComponent component = componentMap.get(record.getLocalResourceId());

            DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
            vo.setId(record.getId());
            vo.setOwnerId(record.getOwnerId());
            vo.setMarketItemId(record.getMarketItemId());
            vo.setLocalResourceId(record.getLocalResourceId());
            vo.setMode(record.getMode());
            vo.setDirectoryId(record.getDirectoryId());
            vo.setContentHash(record.getContentHash());
            vo.setCreateTime(record.getCreateTime());
            vo.setResourceType(resourceType);

            String name = null;
            String icon = null;
            String color = null;
            String summary = null;
            Boolean enabled = null;
            // 连接器编码：物料行被作者删掉时用它兜底显示，避免已装列表出现一行空白
            String connectorType = null;
            if (TYPE_CHAIN.equals(resourceType)) {
                if (chain != null) {
                    name = chain.getChainName();
                    summary = chain.getRemark();
                    enabled = ChainStatusEnum.ENABLED.getCode().equals(chain.getStatus());
                    vo.setStatus(chain.getStatus());
                    vo.setHealth(chainHealthMap.get(chain.getId()));
                }
            } else if (TYPE_COMPONENT.equals(resourceType)) {
                if (component != null) {
                    name = component.getComponentName();
                    icon = component.getIcon();
                    color = component.getColor();
                    summary = component.getDescription();
                    enabled = ComponentStatusEnum.ENABLED.getCode().equals(component.getStatus());
                    vo.setStatus(component.getStatus());
                    // 组件（SHARED）在链里被引用时：前端拿编码反查安装记录行，好显示打开按钮
                    vo.setComponentCode(component.getComponentCode());
                }
            } else if (TYPE_CONNECTOR.equals(resourceType)) {
                // 连接器本体全局共享，启用状态看 def 行；名称/图标/简介统一在下面从物料行补
                DatabusConnectorDef def = defMap.get(record.getLocalResourceId());
                if (def != null) {
                    enabled = ConnectorDefStatusEnum.ENABLED.getCode().equals(def.getStatus());
                    vo.setStatus(def.getStatus());
                    connectorType = def.getType();
                }
            }
            if (item != null) {
                if (StringUtils.isBlank(name)) {
                    name = item.getName();
                }
                if (StringUtils.isBlank(icon)) {
                    icon = item.getIcon();
                }
                if (StringUtils.isBlank(color)) {
                    color = item.getColor();
                }
                if (StringUtils.isBlank(summary)) {
                    summary = item.getSummary();
                }
                // 上架者登录名：管理页行上渲染 @作者（宁缺勿滥，物料行没了就不显示）
                vo.setOwnerCode(item.getOwnerCode());
                // 展示码：安装记录表没有这一列，行副标题的「谁的哪一个」从物料行回填
                vo.setCodeDisplay(item.getCodeDisplay());
            }
            if (StringUtils.isBlank(name) && connectorType != null) {
                name = connectorType;
            }
            vo.setName(name);
            vo.setIcon(icon);
            vo.setColor(color);
            vo.setSummary(summary);
            vo.setEnabled(enabled);
            result.add(vo);
        }
        return result;
    }

    /**
     * 重算一个物料的三个缓存计数（install / ref / forkPublish）。
     */
    public void refreshCounts(Long itemId) {
        if (itemId == null) {
            return;
        }
        DatabusMarketItem item = marketItemMapper.selectById(itemId);
        if (item == null) {
            return;
        }
        Long installCount = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getMarketItemId, itemId));

        int refCount = 0;
        String sourceCode = codeSupport.substringAfterLast(item.getCodeDisplay(), DISPLAY_SEPARATOR);
        if (StringUtils.isNotBlank(sourceCode)) {
            Long count = chainMapper.selectCount(new QueryWrapper<DatabusChain>()
                .apply("cmp_property like {0}", "%" + MarketText.escapeLike(sourceCode) + "%"));
            refCount = count == null ? 0 : count.intValue();
        }

        int forkPublishCount = countForkPublished(itemId, item.getResourceType());

        DatabusMarketItem update = new DatabusMarketItem();
        update.setId(itemId);
        update.setInstallCount(installCount == null ? 0 : installCount.intValue());
        update.setRefCount(refCount);
        update.setForkPublishCount(forkPublishCount);
        marketItemMapper.updateById(update);
    }

    /**
     * 装完之后又发布过的副本有多少个：这个物料被装出去的所有副本里，
     * 链路已发布的、组件改过又发布了的，一共多少条。
     */
    private int countForkPublished(Long itemId, String resourceType) {
        List<Long> localIds = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                .select(DatabusInstall::getLocalResourceId)
                .eq(DatabusInstall::getMarketItemId, itemId)).stream()
            .map(DatabusInstall::getLocalResourceId)
            .filter(Objects::nonNull)
            .toList();
        if (localIds.isEmpty()) {
            return 0;
        }
        Long count;
        if (TYPE_CHAIN.equals(resourceType)) {
            count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .in(DatabusChain::getId, localIds)
                .eq(DatabusChain::getStatus, ChainStatusEnum.ENABLED.getCode()));
        } else if (TYPE_COMPONENT.equals(resourceType)) {
            count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
                .in(DatabusComponent::getId, localIds)
                .gt(DatabusComponent::getVersion, DRAFT_VERSION));
        } else {
            return 0;
        }
        return count == null ? 0 : count.intValue();
    }

    /**
     * 安装结果的返回内容：安装记录 ＋ 物料的展示字段 ＋ 各分支自己的产物
     */
    public DatabusMarketInstallVo toInstallVo(DatabusInstall record, DatabusMarketItem item, Long userId,
                                              InstallOutcome outcome) {
        DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
        vo.setId(record.getId());
        vo.setOwnerId(userId);
        vo.setMarketItemId(record.getMarketItemId());
        vo.setLocalResourceId(outcome.localResourceId());
        vo.setMode(outcome.mode());
        vo.setDirectoryId(outcome.directoryId());
        vo.setComponentCode(outcome.componentCode());
        vo.setContentHash(outcome.contentHash());
        vo.setCreateTime(record.getCreateTime());
        // 复制清单只有装链路才返回（组件不复制，没有这个东西）
        vo.setCascade(outcome.cascade());
        vo.setResourceType(item.getResourceType());
        vo.setName(item.getName());
        vo.setSummary(item.getSummary());
        vo.setIcon(item.getIcon());
        vo.setColor(item.getColor());
        vo.setCodeDisplay(item.getCodeDisplay());
        // 组件直接看本体现在是启用还是停用；链路复制出来是草稿（没发布），没有开关
        vo.setEnabled(TYPE_COMPONENT.equals(item.getResourceType()) && Boolean.TRUE.equals(outcome.componentEnabled()));
        return vo;
    }
}
