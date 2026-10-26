package org.dromara.databus.market;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketCategory;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.domain.vo.DatabusMarketPreviewVo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.MarketResourceTypes;
import org.dromara.databus.market.support.MarketStates;
import org.dromara.databus.market.support.MarketText;
import org.dromara.databus.market.support.MarketVoAssembler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 物料市场——查询（只读，不改任何数据；设计文档 databus-material-market.md §五~§十一）。
 * <p>
 * 只管查这几件事：在线物料分页列表 / 分类计数 / 详情 / 我的上架 / 待审核队列 / 安装前看实现。
 * 列表和详情都会顺手填上「当前用户装了没有」；
 * 安装卸载那些改数据的操作在 {@link MarketInstallService}，上架审核那些在 {@link MarketPublishService}。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketQueryService {

    private static final String TYPE_COMPONENT = MarketResourceTypes.COMPONENT;
    private static final String TYPE_CHAIN = MarketResourceTypes.CHAIN;

    /**
     * 资源类型：连接器（§15.3「什么都能装」：装连接器＝把 def 行改成启用状态、全局生效，
     * 所以判断「装了没有」看的是 def 行的状态，不是个人安装记录）
     */
    private static final String TYPE_CONNECTOR = MarketResourceTypes.CONNECTOR;

    private static final String STATUS_ONLINE = MarketStates.STATUS_ONLINE;
    private static final String STATUS_PENDING = MarketStates.STATUS_PENDING;
    private static final String DEF_STATUS_ENABLED = MarketStates.DEF_STATUS_ENABLED;

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusMarketCategoryMapper marketCategoryMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    private final MarketVoAssembler assembler;

    /**
     * 在线物料的分页列表，装得多的排前面，可以按关键字、分类、类型、作者筛，
     * 每一行都填上当前用户装了没有。
     */
    public PageResult<DatabusMarketItemVo> queryPageList(MarketQueryBo bo, PageQuery pageQuery, Long userId) {
        LambdaQueryWrapper<DatabusMarketItem> lqw = Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getStatus, STATUS_ONLINE);
        if (bo != null) {
            lqw.eq(StringUtils.isNotBlank(bo.getResourceType()),
                DatabusMarketItem::getResourceType, bo.getResourceType());
            lqw.eq(bo.getCategoryId() != null, DatabusMarketItem::getCategoryId, bo.getCategoryId());
            lqw.eq(StringUtils.isNotBlank(bo.getOwnerCode()),
                DatabusMarketItem::getOwnerCode, bo.getOwnerCode());
            if (StringUtils.isNotBlank(bo.getKeyword())) {
                String kw = bo.getKeyword().trim();
                // tags 在实体里是 List<String>（库里是 JSON 列），用 lambda 写 like 类型对不上，
                // 所以改用 apply 直接拼字符串去匹配
                lqw.and(w -> w.like(DatabusMarketItem::getName, kw)
                    .or().like(DatabusMarketItem::getCodeDisplay, kw)
                    .or().like(DatabusMarketItem::getSummary, kw)
                    .or().apply("tags like {0}", "%" + MarketText.escapeLike(kw) + "%"));
            }
        }
        // 排序：先按安装数从多到少，安装数一样的按 id 从大到小（新上架的排前面）
        lqw.orderByDesc(DatabusMarketItem::getInstallCount).orderByDesc(DatabusMarketItem::getId);
        Page<DatabusMarketItem> page = marketItemMapper.selectPage(pageQuery.build(), lqw);

        Map<Long, Long> installedMap = buildInstalledMap(userId, page.getRecords());
        // 连接器这一类不看个人安装记录，改看 def 行是不是启用状态（装连接器＝全局启用，§15.3）
        Map<Long, DatabusConnectorDef> connectorDefMap = connectorDefMap(page.getRecords());
        List<DatabusMarketItemVo> voList = new ArrayList<>(page.getRecords().size());
        for (DatabusMarketItem item : page.getRecords()) {
            DatabusMarketItemVo vo = assembler.toItemVo(item);
            fillInstalled(vo, item, userId, installedMap, connectorDefMap);
            voList.add(vo);
        }
        return PageResult.build(voList, page.getTotal());
    }

    /**
     * 分类列表，每个分类带上「在线物料有多少个」（前端分类标签上的数字角标要用）。
     */
    public List<DatabusMarketCategoryVo> queryCategories() {
        List<DatabusMarketCategory> categories = marketCategoryMapper.selectList(Wrappers.<DatabusMarketCategory>lambdaQuery()
            .orderByAsc(DatabusMarketCategory::getSort).orderByAsc(DatabusMarketCategory::getId));
        // 查在线物料，按分类数一数各有多少个（给前端分类标签上的数字角标用）
        List<DatabusMarketItem> online = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .select(DatabusMarketItem::getCategoryId)
            .eq(DatabusMarketItem::getStatus, STATUS_ONLINE)
            .isNotNull(DatabusMarketItem::getCategoryId));
        Map<Long, Long> countMap = online.stream()
            .map(DatabusMarketItem::getCategoryId)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
        return categories.stream().map(category -> {
            DatabusMarketCategoryVo vo = new DatabusMarketCategoryVo();
            vo.setId(category.getId());
            vo.setParentId(category.getParentId());
            vo.setName(category.getName());
            vo.setSort(category.getSort());
            vo.setItemCount(countMap.getOrDefault(category.getId(), 0L));
            return vo;
        }).toList();
    }

    /**
     * 物料详情：在线的谁都能看，不在线的只有上架的人自己能看（在管理页里回看审核情况）。
     */
    public DatabusMarketItemVo queryById(Long id, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        // 能不能看的规则：在线的谁都能看；不在线的只有上架的人自己能看（在管理页里回看审核情况）
        if (!STATUS_ONLINE.equals(item.getStatus()) && !Objects.equals(item.getOwnerId(), userId)) {
            throw new ServiceException("物料不可见");
        }
        DatabusMarketItemVo vo = assembler.toItemVo(item);
        Map<Long, Long> installedMap = buildInstalledMap(userId, List.of(item));
        fillInstalled(vo, item, userId, installedMap, connectorDefMap(List.of(item)));
        return vo;
    }

    /**
     * 我上架的物料列表（各种审核状态的都列出来），按更新时间从新到旧排。
     */
    public List<DatabusMarketItemVo> queryMine(Long userId) {
        List<DatabusMarketItem> items = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getOwnerId, userId)
            .orderByDesc(DatabusMarketItem::getUpdateTime)
            .orderByDesc(DatabusMarketItem::getId));
        return items.stream().map(assembler::toItemVo).toList();
    }

    /**
     * 待审核列表（给审核人看的）。
     */
    public List<DatabusMarketItemVo> queryPending(Long userId) {
        // 待审核的东西全都列出来，包括自己上架的：审核人需要看到全部待审内容。
        // 自己上架的那几条，由前端按 owner 把操作按钮藏起来（不许审自己的，decisions §七-7.1；
        // 2026-10-24 用户定的：列表里要有，只是自己上架的不给操作权限）
        List<DatabusMarketItem> items = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getStatus, STATUS_PENDING)
            .orderByAsc(DatabusMarketItem::getCreateTime)
            .orderByAsc(DatabusMarketItem::getId));
        return items.stream().map(assembler::toItemVo).toList();
    }

    /**
     * 安装前看看实现：只有已上架的物料才给看；链路给画布上的节点列表，组件给脚本和出入参定义。
     */
    public DatabusMarketPreviewVo preview(Long id) {
        // 安装前可以看（2026-10-24）：只给看已上架的——审核中、已驳回、已下架的物料，
        // 它的实现内容还是作者自己的，不给外人看。
        // 审核人要看内容走 review-detail 那个接口（那边另有规则），普通浏览者一律拿不到
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!STATUS_ONLINE.equals(item.getStatus())) {
            throw new ServiceException("物料尚未上架，暂不提供实现预览");
        }
        DatabusMarketPreviewVo vo = new DatabusMarketPreviewVo();
        vo.setResourceType(item.getResourceType());
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain chain = chainMapper.selectById(item.getSourceId());
            if (chain == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            vo.setChainId(chain.getId());
            vo.setDescription(chain.getRemark());
            List<DatabusMarketReviewDetailVo.NodeVo> nodes = new ArrayList<>();
            if (chain.getCmpProperty() != null) {
                assembler.collectPreviewNodes(chain.getCmpProperty(), nodes, 0);
            }
            vo.setNodes(nodes);
            return vo;
        }
        if (!TYPE_COMPONENT.equals(item.getResourceType())) {
            throw new ServiceException("连接器不支持实现预览");
        }
        DatabusComponent component = componentMapper.selectById(item.getSourceId());
        if (component == null) {
            throw new ServiceException("源组件不存在或已删除");
        }
        vo.setCode(component.getComponentCode());
        vo.setVersion(component.getVersion() == null ? null : String.valueOf(component.getVersion()));
        vo.setScriptLang(component.getScriptLang());
        vo.setScriptBody(component.getScriptBody());
        vo.setParamSchema(component.getParamSchema());
        vo.setInputSchema(component.getInputSchema());
        vo.setOutputSchema(component.getOutputSchema());
        vo.setDescription(component.getDescription());
        return vo;
    }

    /**
     * 算出「这批物料里，当前用户装了哪些」：市场物料 id → 本地副本 id。
     * <p>
     * 先看安装记录；链路物料没有安装记录时，再看我名下是不是已经有一条从同一条源链复制来的副本
     * （2026-10-24 补的缺口A：装链路时会跟着复制一批子链副本，这些副本自动进了「我的」目录，
     * 但安装记录只记主链一条，§十四-5。这样一来子链的卡片就会显示「安装」按钮，
     * 用户点了等于又复制一份——所以要按 source_chain_id 认出它们已经装过了。
     * 判断方式：我名下、没删的链，source_chain_id 等于这个物料的源链 id）。
     * 组件是大家共用同一个，「装」就是写一条安装记录，所以组件不做这个补充判断。
     */
    private Map<Long, Long> buildInstalledMap(Long userId, List<DatabusMarketItem> items) {
        if (userId == null || items == null || items.isEmpty()) {
            return Map.of();
        }
        List<Long> itemIds = items.stream().map(DatabusMarketItem::getId).toList();
        Map<Long, Long> map = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getOwnerId, userId)
                .in(DatabusInstall::getMarketItemId, itemIds)).stream()
            .collect(Collectors.toMap(DatabusInstall::getMarketItemId, DatabusInstall::getLocalResourceId, (a, b) -> a));
        List<DatabusMarketItem> chainItems = items.stream()
            .filter(it -> TYPE_CHAIN.equals(it.getResourceType()) && it.getSourceId() != null && !map.containsKey(it.getId()))
            .toList();
        if (chainItems.isEmpty()) {
            return map;
        }
        List<Long> sourceIds = chainItems.stream().map(DatabusMarketItem::getSourceId).distinct().toList();
        // 这里不用 select 只查几列（实体上有 autoResultMap typeHandler，只查部分列容易出问题，
        // validateNotChainReferenced 那里的注释也说了同样的事）；
        // 同一条源链复制来的副本按理只有一条（会复用，§十四-2），
        // 万一有多条就取先扫到的那条，别报错
        Map<Long, Long> copyBySource = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getCreateBy, userId)
                .in(DatabusChain::getSourceChainId, sourceIds)
                .orderByDesc(DatabusChain::getId)).stream()
            .collect(Collectors.toMap(DatabusChain::getSourceChainId, DatabusChain::getId, (a, b) -> a));
        for (DatabusMarketItem item : chainItems) {
            Long copyId = copyBySource.get(item.getSourceId());
            if (copyId != null) {
                map.put(item.getId(), copyId);
            }
        }
        return map;
    }

    /**
     * 把这批物料里连接器类型的 def 行查出来：market_item.source_id（就是 def.id）→ def 行。
     */
    private Map<Long, DatabusConnectorDef> connectorDefMap(List<DatabusMarketItem> items) {
        List<Long> defIds = items.stream()
            .filter(item -> TYPE_CONNECTOR.equals(item.getResourceType()))
            .map(DatabusMarketItem::getSourceId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (defIds.isEmpty()) {
            return Map.of();
        }
        return connectorDefMapper.selectByIds(defIds).stream()
            .collect(Collectors.toMap(DatabusConnectorDef::getId, def -> def, (a, b) -> a));
    }

    /**
     * 往返回结果里填三个字段：installed（装了没有）/ localResourceId（本地那份的 id）/ mine（是不是我上架的）。
     * <p>
     * 链路和组件看个人安装记录；连接器看 def 行是不是启用状态（装连接器＝全局启用，§15.3），
     * 这时 localResourceId 填的是 def.id——前端拿它去 defs 列表里对上、取出 type，才能做启用停用和卸载。
     */
    private void fillInstalled(DatabusMarketItemVo vo, DatabusMarketItem item, Long userId,
                               Map<Long, Long> installedMap, Map<Long, DatabusConnectorDef> connectorDefMap) {
        if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            DatabusConnectorDef def = connectorDefMap.get(item.getSourceId());
            boolean installed = def != null && DEF_STATUS_ENABLED.equals(def.getStatus());
            vo.setInstalled(installed);
            vo.setLocalResourceId(installed ? def.getId() : null);
        } else {
            Long localResourceId = installedMap.get(item.getId());
            vo.setInstalled(localResourceId != null);
            vo.setLocalResourceId(localResourceId);
        }
        vo.setMine(Objects.equals(item.getOwnerId(), userId));
    }
}
