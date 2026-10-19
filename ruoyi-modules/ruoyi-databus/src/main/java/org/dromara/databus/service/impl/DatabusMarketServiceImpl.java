package org.dromara.databus.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketCategory;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketQueryBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketCategoryVo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.service.IDatabusMarketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 物料市场 Service 实现（设计正本 databus-material-market.md §五~§十一）。
 * <p>
 * 三组能力：
 * <ul>
 *   <li>浏览（只读）：在线物料分页列表 / 分类计数 / 详情，回填「当前用户是否已装」。</li>
 *   <li>消费（安装侧）：安装＝fork（把源资源冻结快照复制进我的目录并记 {@code databus_install} 血缘）；
 *       卸载＝删我的副本 + 删血缘；我的已装列表。</li>
 *   <li>供给（上架侧）：提交上架（进待审，同源重复提交则更新回待审）/ 下架 / 审核 / 删原件（作者主权）。</li>
 * </ul>
 * <p>
 * 命名空间：fork 副本编码统一加 {@code u{ownerId}__} 前缀（双下划线为系统保留分隔符，
 * 源编码不含 {@code __} 故结构性防撞名）；见 §六。计数三列（install/ref/forkPublish）为缓存，
 * fork / 卸载后同步刷新（§九：不进实时路径）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusMarketServiceImpl implements IDatabusMarketService {

    /**
     * 资源类型：组件
     */
    private static final String TYPE_COMPONENT = "COMPONENT";

    /**
     * 资源类型：链路
     */
    private static final String TYPE_CHAIN = "CHAIN";

    /**
     * 上架状态：待审
     */
    private static final String STATUS_PENDING = "PENDING";

    /**
     * 上架状态：上线
     */
    private static final String STATUS_ONLINE = "ONLINE";

    /**
     * 上架状态：下线
     */
    private static final String STATUS_OFFLINE = "OFFLINE";

    /**
     * 上架状态：驳回
     */
    private static final String STATUS_REJECTED = "REJECTED";

    /**
     * 安装模式：fork（复制快照落地）
     */
    private static final String MODE_FORK = "FORK";

    /**
     * 编码最大长度（与 DatabusChainBo / DatabusComponentBo 的 @Size 约束一致）
     */
    private static final int CHAIN_CODE_MAX_LEN = 100;
    private static final int COMPONENT_CODE_MAX_LEN = 64;

    /**
     * 草稿初始版本（与 DatabusChainServiceImpl 同值）
     */
    private static final int DRAFT_VERSION = 1;

    /**
     * 链路状态（databus_chain.status）：草稿 / 已发布
     */
    private static final String CHAIN_STATUS_DRAFT = "0";
    private static final String CHAIN_STATUS_PUBLISHED = "1";

    /**
     * 组件启停（databus_component.status）：启用
     */
    private static final String COMPONENT_STATUS_ENABLED = "0";

    /**
     * 组件废弃标记（0正常）
     */
    private static final String DEPRECATED_NO = "0";

    /**
     * 模板标记（0否）：fork 副本一律剥离模板身份
     */
    private static final String TEMPLATE_FLAG_NO = "0";

    /**
     * 命名空间保留分隔符（源编码不得包含）
     */
    private static final String CODE_SEPARATOR = "__";

    /**
     * 逻辑源码指纹拼接分隔符
     */
    private static final String HASH_SEP = "|";

    /**
     * 展示码 owner 与 code 的分隔符
     */
    private static final String DISPLAY_SEPARATOR = "/";

    /**
     * 副本编码后缀序号上限（防御性）
     */
    private static final int CODE_SEQ_MAX = 9999;

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusMarketCategoryMapper marketCategoryMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    // ---------------------------- 浏览（只读） ----------------------------

    @Override
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
                // tags 为 List<String>（JSON 列），lambda like 类型不匹配 → 用 apply 走字符串匹配
                lqw.and(w -> w.like(DatabusMarketItem::getName, kw)
                    .or().like(DatabusMarketItem::getCodeDisplay, kw)
                    .or().like(DatabusMarketItem::getSummary, kw)
                    .or().apply("tags like {0}", "%" + escapeLike(kw) + "%"));
            }
        }
        // 热度优先：安装数降序、同数按 id 降序（新上架在前）
        lqw.orderByDesc(DatabusMarketItem::getInstallCount).orderByDesc(DatabusMarketItem::getId);
        Page<DatabusMarketItem> page = marketItemMapper.selectPage(pageQuery.build(), lqw);

        List<Long> itemIds = page.getRecords().stream().map(DatabusMarketItem::getId).toList();
        Map<Long, Long> installedMap = installedMap(userId, itemIds);
        List<DatabusMarketItemVo> voList = new ArrayList<>(page.getRecords().size());
        for (DatabusMarketItem item : page.getRecords()) {
            DatabusMarketItemVo vo = toItemVo(item);
            Long localResourceId = installedMap.get(item.getId());
            vo.setInstalled(localResourceId != null);
            vo.setLocalResourceId(localResourceId);
            vo.setMine(Objects.equals(item.getOwnerId(), userId));
            voList.add(vo);
        }
        return PageResult.build(voList, page.getTotal());
    }

    @Override
    public List<DatabusMarketCategoryVo> queryCategories() {
        List<DatabusMarketCategory> categories = marketCategoryMapper.selectList(Wrappers.<DatabusMarketCategory>lambdaQuery()
            .orderByAsc(DatabusMarketCategory::getSort).orderByAsc(DatabusMarketCategory::getId));
        // 在线物料按分类分组计数（chips 角标用）
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

    @Override
    public DatabusMarketItemVo queryById(Long id, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        // 详情可见性：在线物料人人可见；非在线仅上架者本人可见（用于管理页回看审核态）
        if (!STATUS_ONLINE.equals(item.getStatus()) && !Objects.equals(item.getOwnerId(), userId)) {
            throw new ServiceException("物料不可见");
        }
        DatabusMarketItemVo vo = toItemVo(item);
        Map<Long, Long> installedMap = installedMap(userId, List.of(item.getId()));
        Long localResourceId = installedMap.get(item.getId());
        vo.setInstalled(localResourceId != null);
        vo.setLocalResourceId(localResourceId);
        vo.setMine(Objects.equals(item.getOwnerId(), userId));
        return vo;
    }

    // ---------------------------- 消费（安装侧） ----------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatabusMarketInstallVo install(Long itemId, Long directoryId, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(itemId);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!STATUS_ONLINE.equals(item.getStatus())) {
            throw new ServiceException("物料未上线，暂不可安装");
        }
        Long installed = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getMarketItemId, itemId));
        if (installed != null && installed > 0) {
            throw new ServiceException("你已安装该物料，无需重复安装");
        }

        Long targetDirectoryId = normalizeDirectoryId(directoryId);
        String prefix = namespacePrefix(userId);
        Long localResourceId;
        String contentHash;
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain source = chainMapper.selectById(item.getSourceId());
            if (source == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            String code = buildUniqueCode(prefix, source.getChainCode(), CHAIN_CODE_MAX_LEN, this::existsChainCode);
            DatabusChain copy = new DatabusChain();
            copy.setChainCode(code);
            copy.setChainName(source.getChainName());
            copy.setVersion(DRAFT_VERSION);
            copy.setStatus(CHAIN_STATUS_DRAFT);
            copy.setElExpression(source.getElExpression());
            copy.setCanvasData(source.getCanvasData());
            copy.setCmpProperty(source.getCmpProperty());
            copy.setLogLevel(source.getLogLevel());
            copy.setInputParams(source.getInputParams());
            copy.setDirectoryId(targetDirectoryId);
            copy.setIsTemplate(TEMPLATE_FLAG_NO);
            copy.setRemark(source.getRemark());
            chainMapper.insert(copy);
            localResourceId = copy.getId();
            contentHash = chainHash(source);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            DatabusComponent source = componentMapper.selectById(item.getSourceId());
            if (source == null) {
                throw new ServiceException("源组件不存在或已删除");
            }
            String code = buildUniqueCode(prefix, source.getComponentCode(), COMPONENT_CODE_MAX_LEN, this::existsComponentCode);
            DatabusComponent copy = new DatabusComponent();
            copy.setComponentCode(code);
            copy.setComponentName(source.getComponentName());
            copy.setShortName(source.getShortName());
            copy.setCategory(source.getCategory());
            copy.setGroupName(source.getGroupName());
            copy.setDomain(source.getDomain());
            copy.setDirectoryId(targetDirectoryId);
            copy.setIcon(source.getIcon());
            copy.setColor(source.getColor());
            copy.setSort(source.getSort());
            copy.setDescription(source.getDescription());
            copy.setTags(source.getTags());
            copy.setNodeType(source.getNodeType());
            copy.setEditor(source.getEditor());
            copy.setParamSchema(source.getParamSchema());
            copy.setDataExample(source.getDataExample());
            copy.setInputSchema(source.getInputSchema());
            copy.setOutputSchema(source.getOutputSchema());
            copy.setScriptLang(source.getScriptLang());
            copy.setScriptBody(source.getScriptBody());
            copy.setVersion(DRAFT_VERSION);
            copy.setStatus(COMPONENT_STATUS_ENABLED);
            copy.setDeprecated(DEPRECATED_NO);
            copy.setDeprecateNote(source.getDeprecateNote());
            copy.setDocUrl(source.getDocUrl());
            copy.setRemark(source.getRemark());
            componentMapper.insert(copy);
            localResourceId = copy.getId();
            contentHash = componentHash(source);
        } else {
            throw new ServiceException("不支持的物料类型：" + item.getResourceType());
        }

        DatabusInstall record = new DatabusInstall();
        record.setOwnerId(userId);
        record.setMarketItemId(itemId);
        record.setLocalResourceId(localResourceId);
        record.setMode(MODE_FORK);
        record.setContentHash(contentHash);
        record.setCreateTime(LocalDateTime.now());
        installMapper.insert(record);

        refreshCounts(itemId);

        DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
        vo.setId(record.getId());
        vo.setOwnerId(userId);
        vo.setMarketItemId(itemId);
        vo.setLocalResourceId(localResourceId);
        vo.setMode(MODE_FORK);
        vo.setContentHash(contentHash);
        vo.setCreateTime(record.getCreateTime());
        vo.setResourceType(item.getResourceType());
        vo.setName(item.getName());
        vo.setSummary(item.getSummary());
        vo.setIcon(item.getIcon());
        vo.setColor(item.getColor());
        // 刚 fork：组件为启用态；链路为草稿（未发布），无开关
        vo.setEnabled(TYPE_COMPONENT.equals(item.getResourceType()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(Long resourceId, Long userId) {
        DatabusInstall record = installMapper.selectOne(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getLocalResourceId, resourceId)
            .orderByDesc(DatabusInstall::getId)
            .last("limit 1"));
        if (record == null) {
            throw new ServiceException("未找到对应的安装记录");
        }
        // 类型：优先查市场物料；源已删时按本地副本落在哪张表推断
        String resourceType = null;
        if (record.getMarketItemId() != null) {
            DatabusMarketItem item = marketItemMapper.selectById(record.getMarketItemId());
            if (item != null) {
                resourceType = item.getResourceType();
            }
        }
        if (resourceType == null) {
            resourceType = inferLocalType(resourceId);
        }
        if (TYPE_CHAIN.equals(resourceType)) {
            chainMapper.deleteById(resourceId);
        } else if (TYPE_COMPONENT.equals(resourceType)) {
            componentMapper.deleteById(resourceId);
        }
        installMapper.deleteById(record.getId());
        if (record.getMarketItemId() != null) {
            refreshCounts(record.getMarketItemId());
        }
        // 不硬拦引用：本地链路仍在用该副本由前端给软提示（§八）
        return true;
    }

    @Override
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

        List<DatabusMarketInstallVo> result = new ArrayList<>(records.size());
        for (DatabusInstall record : records) {
            DatabusMarketItem item = itemMap.get(record.getMarketItemId());
            DatabusChain chain = chainMap.get(record.getLocalResourceId());
            DatabusComponent component = componentMap.get(record.getLocalResourceId());
            String resourceType = item != null ? item.getResourceType()
                : (chain != null ? TYPE_CHAIN : (component != null ? TYPE_COMPONENT : null));

            DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
            vo.setId(record.getId());
            vo.setOwnerId(record.getOwnerId());
            vo.setMarketItemId(record.getMarketItemId());
            vo.setLocalResourceId(record.getLocalResourceId());
            vo.setMode(record.getMode());
            vo.setContentHash(record.getContentHash());
            vo.setCreateTime(record.getCreateTime());
            vo.setResourceType(resourceType);

            String name = null;
            String icon = null;
            String color = null;
            String summary = null;
            Boolean enabled = null;
            if (TYPE_CHAIN.equals(resourceType)) {
                if (chain != null) {
                    name = chain.getChainName();
                    summary = chain.getRemark();
                    enabled = CHAIN_STATUS_PUBLISHED.equals(chain.getStatus());
                }
            } else if (TYPE_COMPONENT.equals(resourceType)) {
                if (component != null) {
                    name = component.getComponentName();
                    icon = component.getIcon();
                    color = component.getColor();
                    summary = component.getDescription();
                    enabled = COMPONENT_STATUS_ENABLED.equals(component.getStatus());
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

    // ---------------------------- 供给（上架侧） ----------------------------

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean publish(MarketPublishBo bo, Long userId, String ownerCode) {
        String resourceType = bo.getResourceType();
        String sourceCode;
        String name;
        String icon = null;
        String color = null;
        Long sourceCreateBy;
        if (TYPE_CHAIN.equals(resourceType)) {
            DatabusChain source = chainMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            sourceCode = source.getChainCode();
            name = source.getChainName();
            sourceCreateBy = source.getCreateBy();
        } else if (TYPE_COMPONENT.equals(resourceType)) {
            DatabusComponent source = componentMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源组件不存在或已删除");
            }
            sourceCode = source.getComponentCode();
            name = source.getComponentName();
            icon = source.getIcon();
            color = source.getColor();
            sourceCreateBy = source.getCreateBy();
        } else {
            throw new ServiceException("不支持的物料类型：" + resourceType);
        }
        // 归属校验（§七 7.1）：系统资源（create_by 空）仅治理角色可上架；其余仅作者本人可上架
        if (sourceCreateBy == null) {
            if (!StpUtil.hasPermission("databus:market:review")) {
                throw new ServiceException("系统组件仅治理角色可上架");
            }
        } else if (!Objects.equals(sourceCreateBy, userId)) {
            throw new ServiceException("只能上架属于自己的资源");
        }
        if (StringUtils.isBlank(sourceCode)) {
            throw new ServiceException("源资源编码为空，无法上架");
        }
        if (sourceCode.contains(CODE_SEPARATOR)) {
            throw new ServiceException("源编码含系统保留分隔符 \"" + CODE_SEPARATOR + "\"，无法上架");
        }

        String codeDisplay = ownerCode + DISPLAY_SEPARATOR + sourceCode;
        LocalDateTime now = LocalDateTime.now();
        DatabusMarketItem existing = marketItemMapper.selectOne(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getOwnerId, userId)
            .eq(DatabusMarketItem::getResourceType, resourceType)
            .eq(DatabusMarketItem::getSourceId, bo.getSourceId())
            .last("limit 1"));
        if (existing != null) {
            // 同源重复提交：更新展示信息并回到待审（清空旧驳回理由与上一轮审核痕迹）
            existing.setName(name);
            existing.setIcon(icon);
            existing.setColor(color);
            existing.setSummary(bo.getSummary());
            existing.setCategoryId(bo.getCategoryId());
            existing.setTags(bo.getTags());
            existing.setCodeDisplay(codeDisplay);
            existing.setStatus(STATUS_PENDING);
            existing.setRemark("");
            existing.setUpdateTime(now);
            boolean updated = marketItemMapper.updateById(existing) > 0;
            // updateById 默认 NOT_NULL，无法把 review_by/review_time 置空 → 显式清审核痕迹
            marketItemMapper.update(null, Wrappers.<DatabusMarketItem>lambdaUpdate()
                .eq(DatabusMarketItem::getId, existing.getId())
                .set(DatabusMarketItem::getReviewBy, null)
                .set(DatabusMarketItem::getReviewTime, null));
            return updated;
        }

        DatabusMarketItem add = new DatabusMarketItem();
        add.setOwnerId(userId);
        add.setOwnerCode(ownerCode);
        add.setResourceType(resourceType);
        add.setSourceId(bo.getSourceId());
        add.setCodeDisplay(codeDisplay);
        add.setName(name);
        add.setSummary(bo.getSummary());
        add.setIcon(icon);
        add.setColor(color);
        add.setCategoryId(bo.getCategoryId());
        add.setTags(bo.getTags());
        add.setStatus(STATUS_PENDING);
        add.setInstallCount(0);
        add.setRefCount(0);
        add.setForkPublishCount(0);
        add.setCreateTime(now);
        add.setUpdateTime(now);
        return marketItemMapper.insert(add) > 0;
    }

    @Override
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

    @Override
    public Boolean review(Long id, MarketReviewBo bo, Long reviewerId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!STATUS_PENDING.equals(item.getStatus())) {
            throw new ServiceException("仅待审物料可审核");
        }
        // 禁自审：治理者不得审核自己上架的物料（§七 7.1）
        if (Objects.equals(item.getOwnerId(), reviewerId)) {
            throw new ServiceException("不能审核自己上架的物料");
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
        } else {
            if (StringUtils.isBlank(bo.getReason())) {
                throw new ServiceException("驳回需填写理由");
            }
            update.setStatus(STATUS_REJECTED);
            update.setRemark(bo.getReason());
        }
        return marketItemMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteItem(Long id, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!Objects.equals(item.getOwnerId(), userId)) {
            throw new ServiceException("只能删除自己上架的物料");
        }
        // 物理删（databus_market_item 无逻辑删除）；下游已装副本靠断奶快照自保，install 血缘行保留（悬空由 queryInstalled 兜底）
        return marketItemMapper.deleteById(id) > 0;
    }

    // ---------------------------- 治理视图（我的上架 / 待审核） ----------------------------

    @Override
    public List<DatabusMarketItemVo> queryMine(Long userId) {
        List<DatabusMarketItem> items = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getOwnerId, userId)
            .orderByDesc(DatabusMarketItem::getUpdateTime)
            .orderByDesc(DatabusMarketItem::getId));
        return items.stream().map(this::toItemVo).toList();
    }

    @Override
    public List<DatabusMarketItemVo> queryPending() {
        List<DatabusMarketItem> items = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getStatus, STATUS_PENDING)
            .orderByAsc(DatabusMarketItem::getCreateTime)
            .orderByAsc(DatabusMarketItem::getId));
        return items.stream().map(this::toItemVo).toList();
    }

    // ---------------------------- 计数刷新 ----------------------------

    /**
     * 刷新单个物料的三个缓存计数（install / ref / forkPublish）。
     */
    private void refreshCounts(Long itemId) {
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
        String sourceCode = substringAfterLast(item.getCodeDisplay(), DISPLAY_SEPARATOR);
        if (StringUtils.isNotBlank(sourceCode)) {
            Long count = chainMapper.selectCount(new QueryWrapper<DatabusChain>()
                .apply("cmp_property like {0}", "%" + escapeLike(sourceCode) + "%"));
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
     * fork 改后发布数：本物料的安装副本中，已发布链路 / 改后发布组件的条数。
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
                .eq(DatabusChain::getStatus, CHAIN_STATUS_PUBLISHED));
        } else if (TYPE_COMPONENT.equals(resourceType)) {
            count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
                .in(DatabusComponent::getId, localIds)
                .gt(DatabusComponent::getVersion, DRAFT_VERSION));
        } else {
            return 0;
        }
        return count == null ? 0 : count.intValue();
    }

    // ---------------------------- 私有工具 ----------------------------

    /**
     * 当前用户对指定物料集合的已装映射：市场物料 id → 本地副本 id。
     */
    private Map<Long, Long> installedMap(Long userId, List<Long> itemIds) {
        if (userId == null || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        return installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getOwnerId, userId)
                .in(DatabusInstall::getMarketItemId, itemIds)).stream()
            .collect(Collectors.toMap(DatabusInstall::getMarketItemId, DatabusInstall::getLocalResourceId, (a, b) -> a));
    }

    /**
     * 目标目录归一：null 或非正数 → 未归组（null）。
     */
    private Long normalizeDirectoryId(Long directoryId) {
        return (directoryId == null || directoryId <= 0) ? null : directoryId;
    }

    /**
     * owner 命名空间前缀：{@code u{ownerId}__}（双下划线为系统保留分隔符）。
     */
    private String namespacePrefix(Long userId) {
        return "u" + userId + CODE_SEPARATOR;
    }

    /**
     * 生成 fork 副本编码：{@code prefix + sourceCode} 截断至 maxLen；撞名追加 _2/_3…（同样受长度约束）。
     */
    private String buildUniqueCode(String prefix, String sourceCode, int maxLen, Predicate<String> exists) {
        String code = sourceCode == null ? "" : sourceCode;
        String base = truncate(prefix + code, maxLen);
        if (!exists.test(base)) {
            return base;
        }
        for (int seq = 2; seq <= CODE_SEQ_MAX; seq++) {
            String suffix = "_" + seq;
            String head = truncate(prefix + code, maxLen - suffix.length());
            String candidate = head + suffix;
            if (!exists.test(candidate)) {
                return candidate;
            }
        }
        throw new ServiceException("无法为安装副本生成唯一编码，请缩短源编码长度");
    }

    /**
     * 截断到最大长度。
     */
    private String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        int limit = Math.max(0, maxLen);
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private boolean existsChainCode(String code) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, code));
        return count != null && count > 0;
    }

    private boolean existsComponentCode(String code) {
        Long count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getComponentCode, code));
        return count != null && count > 0;
    }

    /**
     * 按本地副本落在哪张表推断资源类型（源已删场景）。
     */
    private String inferLocalType(Long localResourceId) {
        if (chainMapper.selectById(localResourceId) != null) {
            return TYPE_CHAIN;
        }
        if (componentMapper.selectById(localResourceId) != null) {
            return TYPE_COMPONENT;
        }
        return null;
    }

    /**
     * 链路逻辑源码指纹（画布 + EL）。
     */
    private String chainHash(DatabusChain chain) {
        String raw = nullToEmpty(chain.getCanvasData()) + HASH_SEP + nullToEmpty(chain.getElExpression());
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 组件逻辑源码指纹（脚本正文 + 参数 schema + 节点类型 + 配置形态）。
     */
    private String componentHash(DatabusComponent component) {
        String raw = nullToEmpty(component.getScriptBody()) + HASH_SEP
            + nullToEmpty(component.getParamSchema()) + HASH_SEP
            + nullToEmpty(component.getNodeType()) + HASH_SEP
            + nullToEmpty(component.getEditor());
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 取字符串最后一个分隔符之后的部分（无分隔符则原样返回）。
     */
    private String substringAfterLast(String value, String separator) {
        if (value == null) {
            return null;
        }
        int idx = value.lastIndexOf(separator);
        return idx >= 0 ? value.substring(idx + separator.length()) : value;
    }

    /**
     * 转义 MySQL LIKE 通配符（MySQL 默认转义符为反斜杠）。
     */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

    /**
     * 实体 → 市场物料视图（不含消费侧回填）。
     */
    private DatabusMarketItemVo toItemVo(DatabusMarketItem item) {
        DatabusMarketItemVo vo = new DatabusMarketItemVo();
        vo.setId(item.getId());
        vo.setOwnerId(item.getOwnerId());
        vo.setOwnerCode(item.getOwnerCode());
        vo.setResourceType(item.getResourceType());
        vo.setSourceId(item.getSourceId());
        vo.setCodeDisplay(item.getCodeDisplay());
        vo.setName(item.getName());
        vo.setSummary(item.getSummary());
        vo.setIcon(item.getIcon());
        vo.setColor(item.getColor());
        vo.setCategoryId(item.getCategoryId());
        vo.setTags(item.getTags());
        vo.setStatus(item.getStatus());
        vo.setInstallCount(item.getInstallCount());
        vo.setRefCount(item.getRefCount());
        vo.setForkPublishCount(item.getForkPublishCount());
        vo.setCreateTime(item.getCreateTime());
        vo.setUpdateTime(item.getUpdateTime());
        vo.setRemark(item.getRemark());
        vo.setReviewBy(item.getReviewBy());
        vo.setReviewTime(item.getReviewTime());
        return vo;
    }

}