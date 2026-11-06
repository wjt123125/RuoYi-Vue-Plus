package org.dromara.databus.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorManifest;
import org.dromara.databus.connector.ConnectorRegistry;
import org.dromara.databus.connector.plugin.PluginManager;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusConnectorDefVo;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.MarketResourceTypes;
import org.dromara.databus.market.support.MarketStates;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 连接器 def 台账的查询与视图组装（从 DatabusConnectorServiceImpl 拆出的只读组装器）。
 *
 * <p>职责边界：只负责「def 行怎么查、怎么变成 VO」，不碰上传、启停、装载、卸载。
 * 启停与卸载会改变 def 状态，那些动作留在 Service 里；本类只保证同一套可见性口径。
 *
 * <p>可见性：治理者（超管或有 {@code databus:connector:edit} 权限）看全量台账；
 * 其他人只看「已启用且已上架」——上架者若拿到全量，会把别人未审核的 PENDING 包当成货架内容。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class ConnectorDefViewAssembler {

    private final DatabusConnectorDefMapper defMapper;

    /**
     * 市场物料行（反查上架者 owner_code：def 表自己没有作者列）
     */
    private final DatabusMarketItemMapper marketItemMapper;

    /**
     * 安装记录（判断「我的已装连接器」用）
     */
    private final DatabusInstallMapper installMapper;

    private final ConnectorRegistry connectorRegistry;

    private final PluginManager pluginManager;

    /**
     * manifest 解析用 Jackson3 mapper（容器 bean）
     */
    private final ObjectMapper objectMapper;

    /**
     * 全量台账列表（治理者看全部，其他用户只看已启用且已上架）。
     */
    public List<DatabusConnectorDefVo> listDefs() {
        boolean fullView = LoginHelper.isSuperAdmin()
            || StpUtil.hasPermission("databus:connector:edit");
        List<DatabusConnectorDef> defs = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .eq(!fullView, DatabusConnectorDef::getStatus, ConnectorDefStatusEnum.ENABLED.getCode())
            .orderByAsc(DatabusConnectorDef::getType));
        if (!fullView) {
            Set<Long> onlineDefIds = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, MarketResourceTypes.CONNECTOR)
                    .eq(DatabusMarketItem::getStatus, MarketStates.STATUS_ONLINE))
                .stream()
                .map(DatabusMarketItem::getSourceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            defs.removeIf(def -> !onlineDefIds.contains(def.getId()));
        }
        List<DatabusConnectorDefVo> vos = defs.stream()
            .map(this::toVo)
            .collect(Collectors.toList());
        fillOwnerCode(defs, vos);
        return vos;
    }

    /**
     * 我的已装连接器：只返回当前用户有安装记录、且处于已启用状态的 def 行。
     * 已装列表和建链时选连接器都走这里，没装过的连接器不会出现。
     */
    public List<DatabusConnectorDefVo> listMyDefs() {
        Long userId = LoginHelper.getUserId();
        Set<Long> itemIds = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getOwnerId, userId))
            .stream()
            .map(DatabusInstall::getMarketItemId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (itemIds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> defIds = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .in(DatabusMarketItem::getId, itemIds)
                .eq(DatabusMarketItem::getResourceType, MarketResourceTypes.CONNECTOR))
            .stream()
            .map(DatabusMarketItem::getSourceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (defIds.isEmpty()) {
            return new ArrayList<>();
        }
        List<DatabusConnectorDef> defs = defMapper.selectList(Wrappers.<DatabusConnectorDef>lambdaQuery()
            .in(DatabusConnectorDef::getId, defIds)
            .eq(DatabusConnectorDef::getStatus, ConnectorDefStatusEnum.ENABLED.getCode())
            .orderByAsc(DatabusConnectorDef::getType));
        List<DatabusConnectorDefVo> vos = defs.stream()
            .map(this::toVo)
            .collect(Collectors.toList());
        fillOwnerCode(defs, vos);
        return vos;
    }

    /**
     * 批量回填上架者登录名（管理页连接器行渲染 @作者用）。
     * <p>def 表没有作者列（create_by 存的是上传者用户 id，不是登录名），所以走市场物料行反查：
     * {@code source_id = def.id AND resource_type = CONNECTOR} 取 owner_code。一次 in 查询避免 N+1；
     * 没上架过的 def 查不到就留空——宁缺勿滥，不用 create_by 反查昵称兜底（上传人不等于上架人）。
     */
    private void fillOwnerCode(List<DatabusConnectorDef> defs, List<DatabusConnectorDefVo> vos) {
        List<Long> defIds = defs.stream()
            .map(DatabusConnectorDef::getId).filter(Objects::nonNull).toList();
        if (defIds.isEmpty()) {
            return;
        }
        // 同一 def 可能有多条物料行（驳回后重提等），按 id 升序取，后写的覆盖前面的
        Map<Long, String> ownerMap = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .select(DatabusMarketItem::getSourceId, DatabusMarketItem::getOwnerCode)
                .eq(DatabusMarketItem::getResourceType, MarketResourceTypes.CONNECTOR)
                .in(DatabusMarketItem::getSourceId, defIds)
                .orderByAsc(DatabusMarketItem::getId))
            .stream()
            .filter(item -> item.getSourceId() != null && item.getOwnerCode() != null && !item.getOwnerCode().isBlank())
            .collect(Collectors.toMap(DatabusMarketItem::getSourceId, DatabusMarketItem::getOwnerCode, (a, b) -> b));
        for (int i = 0; i < defs.size(); i++) {
            String ownerCode = ownerMap.get(defs.get(i).getId());
            if (ownerCode != null) {
                vos.get(i).setOwnerCode(ownerCode);
            }
        }
    }

    /**
     * def 行 → Vo：manifest 快照解析回填 label / icon / operations / configSchema
     * （快照损坏时字段置空不抛——列表可用性优先，落库前已过 validate）。
     */
    public DatabusConnectorDefVo toVo(DatabusConnectorDef def) {
        DatabusConnectorDefVo vo = new DatabusConnectorDefVo();
        vo.setId(def.getId());
        vo.setType(def.getType());
        vo.setVersion(def.getVersion());
        vo.setEngine(def.getEngine());
        vo.setStatus(def.getStatus());
        vo.setSource(def.getSource());
        vo.setVerified(def.getVerified());
        vo.setPackagePath(def.getPackagePath());
        vo.setCreateBy(def.getCreateBy());
        vo.setCreateTime(def.getCreateTime());
        vo.setUpdateTime(def.getUpdateTime());
        try {
            ConnectorManifest manifest = objectMapper.readValue(def.getManifestSnapshot(), ConnectorManifest.class);
            vo.setArtifact(manifest.getArtifact());
            vo.setLabel(manifest.getLabel());
            vo.setIcon(manifest.getIcon());
            vo.setOperations(manifest.getOperations());
            vo.setConfigSchema(manifest.getConfigSchema());
        } catch (Exception ignored) {
            // 快照异常不阻断列表；上传链路已保证落库快照合法
        }
        // 已装载时用运行态 describe() 覆盖 operations/configSchema（代码是活的，manifest 是构建期快照，§2.3）
        Connector connector = connectorRegistry.get(def.getType());
        if (connector != null) {
            ConnectorDescriptor descriptor = connector.describe();
            vo.setOperations(descriptor.getOperations());
            vo.setConfigSchema(descriptor.getConfigSchema());
        }
        // 运行态健康（§4.2.1）：内存现算不落库，与组件的 GET /component/script/runtime 同一口径
        PluginManager.ConnectorHealth health = pluginManager.healthOf(def.getType());
        vo.setHealth(health.health());
        vo.setHealthReason(health.reason());
        return vo;
    }
}
