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
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketCategory;
import org.dromara.databus.domain.DatabusMarketItem;
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
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.ChainForkRedirector;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.IDatabusMarketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *   <li>消费（安装侧）：链路＝fork 依赖闭包快照（decisions §十四-1：主链＋全部 subChain
 *       逐条复制独立成行顶层平铺，主链记 {@code databus_install} 血缘、级联以 source_chain_id 溯源）；
 *       组件＝共享注册（不落副本，localResourceId 直接指向源组件，上游实时生效）；
 *       连接器＝enable（万物皆包 §15.3：置 def 行 ENABLED，全局生效，不写台账不落副本）；
 *       卸载按 mode 分流（仅 FORK 删本地副本，SHARED 只删安装记录；连接器卸载走 defs 端点，不经市场）。</li>
 *   <li>供给（上架侧）：提交上架（进待审，同源重复提交则更新回待审）/ 下架 / 审核 / 删原件（作者主权）。</li>
 * </ul>
 * <p>
 * 命名空间：链路 fork 副本编码统一加 {@code u{ownerId}__} 前缀（双下划线为系统保留分隔符，
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
     * 资源类型：连接器（万物皆包 §15.3：安装语义＝enable def 行，非 fork 不写 databus_install）
     */
    private static final String TYPE_CONNECTOR = "CONNECTOR";

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
     * 安装模式：fork（复制快照落地，链路安装专用）
     */
    private static final String MODE_FORK = "FORK";

    /**
     * 安装模式：共享注册（组件安装专用：不落副本，localResourceId 指向源组件，上游更新/下架实时生效）
     */
    private static final String MODE_SHARED = "SHARED";

    /**
     * 编码最大长度（与 DatabusChainBo 的 @Size 约束一致；组件安装已改共享注册，不再生成副本编码）
     */
    private static final int CHAIN_CODE_MAX_LEN = 100;

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
     * 连接器 def 行状态（databus_connector_def.status，与 DatabusConnectorServiceImpl 同值）：启用。
     * installed 判断用（连接器安装＝enable，全局生效非个人台账）
     */
    private static final String DEF_STATUS_ENABLED = "ENABLED";

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

    /**
     * 级联清单复制状态：本次新复制（decisions §十四-1 全 fork 逐条独立成行）
     */
    private static final String CASCADE_NEW = "NEW";

    /**
     * 级联清单复制状态：复用既有同源 fork 品（decisions §十四-2 撞名显式复用，「使用你现有副本」）
     */
    private static final String CASCADE_REUSED = "REUSED";

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusMarketCategoryMapper marketCategoryMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * manifest 快照解析用 Jackson3 mapper（构造注入容器 bean：平台为 Spring Boot4/Jackson3，
     * 容器提供的是 tools.jackson 的 ObjectMapper/JsonMapper，携全局序列化定制）
     */
    private final ObjectMapper objectMapper;

    /**
     * 链路域服务：install 闭包收集（decisions §十四-1，块1 落点与 collectChainRefs 同族同文件）
     */
    private final IDatabusChainService chainService;

    /**
     * EL 生成器：副本 elExpression 一律由重定向后的组件树实时重生成（decisions §十四-3，
     * copy() 先例——无手写 EL 直编模式，生成器产出天然语义等价）
     */
    private final ExpressGenerator expressGenerator;

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

        Map<Long, Long> installedMap = buildInstalledMap(userId, page.getRecords());
        // 连接器行 installed 判断走 def 行状态（安装＝enable 全局生效），不查个人台账（§15.3）
        Map<Long, DatabusConnectorDef> connectorDefMap = connectorDefMap(page.getRecords());
        List<DatabusMarketItemVo> voList = new ArrayList<>(page.getRecords().size());
        for (DatabusMarketItem item : page.getRecords()) {
            DatabusMarketItemVo vo = toItemVo(item);
            fillInstalled(vo, item, userId, installedMap, connectorDefMap);
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
        Map<Long, Long> installedMap = buildInstalledMap(userId, List.of(item));
        fillInstalled(vo, item, userId, installedMap, connectorDefMap(List.of(item)));
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

        // 连接器（§15.3）：安装语义＝enable def 行——非 fork、无目录选择、不写 databus_install
        // （那是链路/组件的血缘台账）；启停/卸载统一走 /databus/connector/defs 端点
        if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            DatabusConnectorDef def = connectorDefMapper.selectById(item.getSourceId());
            if (def == null) {
                throw new ServiceException("源连接器不存在或已卸载");
            }
            if (DEF_STATUS_ENABLED.equals(def.getStatus())) {
                throw new ServiceException("该连接器已启用，无需重复安装");
            }
            DatabusConnectorDef update = new DatabusConnectorDef();
            update.setId(def.getId());
            update.setStatus(DEF_STATUS_ENABLED);
            connectorDefMapper.updateById(update);
            // install_count 是 databus_install 计数缓存，连接器不写台账 → 不刷（§15.3）
            DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
            vo.setOwnerId(userId);
            vo.setMarketItemId(itemId);
            vo.setLocalResourceId(def.getId());
            vo.setMode(MODE_SHARED);
            vo.setEnabled(true);
            vo.setResourceType(TYPE_CONNECTOR);
            vo.setName(item.getName());
            vo.setSummary(item.getSummary());
            vo.setIcon(item.getIcon());
            vo.setColor(item.getColor());
            vo.setCreateTime(LocalDateTime.now());
            return vo;
        }

        Long installed = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getMarketItemId, itemId));
        if (installed != null && installed > 0) {
            throw new ServiceException("你已安装该物料，无需重复安装");
        }

        Long localResourceId;
        String contentHash;
        String mode;
        Boolean componentEnabled = null;
        String componentCode = null;
        Long installDirectoryId = null;
        // 链路闭包级联清单（仅 CHAIN 分支填充）：主链＋级联子链逐条 NEW/REUSED（decisions §十四-2）
        List<DatabusMarketInstallVo.CascadeItemVo> cascade = null;
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            // 链路＝fork 依赖闭包快照（decisions §十四-1）：主链与全部 subChain（含嵌套）逐条复制、
            // 独立成行顶层平铺进我的目录，u{ownerId}__ 前缀防撞名（目录与前缀仅链路分支使用）
            Long targetDirectoryId = normalizeDirectoryId(directoryId);
            String prefix = namespacePrefix(userId);
            DatabusChain source = chainMapper.selectById(item.getSourceId());
            if (source == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            // 块1 两闸门前置：闭包收集（post-order，被引用者先于引用者）＋市场校验（§十四-4：
            // 闭包全链必须 ONLINE——改版冻结 §十四-8 期间即拒装，安装者拿到的永远是全链过审快照）
            LinkedHashMap<String, DatabusChain> closure = chainService.collectChainClosure(source.getChainCode());
            validateClosureOnMarket(closure);
            // post-order 逐条复制：被引用者先落库，引用者改写 CHAIN 引用时子链新 code 已知；
            // 原码→新码映射驱动三字段重定向（cmpProperty CHAIN 叶子 / canvasData data.cmpId / EL 重生成）
            Map<String, String> codeMapping = new HashMap<>();
            cascade = new ArrayList<>(closure.size());
            Long mainCopyId = null;
            for (DatabusChain sourceChain : closure.values()) {
                boolean main = Objects.equals(sourceChain.getId(), source.getId());
                String newCode;
                String cascadeStatus;
                DatabusChain existing = findSameSourceCopy(prefix, sourceChain);
                if (existing != null) {
                    // 撞名显式复用（decisions §十四-2）：候选码命中既有同源 fork 品（幂等重装/跨物料
                    // 闭包已复制）→ 复用该行不复制——复用的是自己 fork 的副本，显式知情
                    newCode = existing.getChainCode();
                    cascadeStatus = CASCADE_REUSED;
                    if (main) {
                        mainCopyId = existing.getId();
                    }
                } else {
                    // 未撞名/异源撞名（含存量 NULL）：后缀唯一化兜底（buildUniqueCode 语义保留，
                    // 仅让位给同源复用；存量不迁移，重新安装即修复断奶不彻底的旧副本）
                    newCode = buildUniqueCode(prefix, sourceChain.getChainCode(), CHAIN_CODE_MAX_LEN, this::existsChainCode);
                    cascadeStatus = CASCADE_NEW;
                    // 引用重定向（decisions §十四-3）：深拷贝改写 CHAIN 叶子 code，副本不指回源命名空间
                    CmpProperty redirectedTree = ChainForkRedirector.redirectChainRefs(sourceChain.getCmpProperty(), codeMapping);
                    DatabusChain copy = new DatabusChain();
                    copy.setChainCode(newCode);
                    copy.setChainName(sourceChain.getChainName());
                    copy.setVersion(DRAFT_VERSION);
                    copy.setStatus(CHAIN_STATUS_DRAFT);
                    copy.setLogLevel(sourceChain.getLogLevel());
                    copy.setInputParams(sourceChain.getInputParams());
                    copy.setDirectoryId(targetDirectoryId);
                    copy.setIsTemplate(TEMPLATE_FLAG_NO);
                    copy.setRemark(sourceChain.getRemark());
                    // 溯源标记（decisions §十四-2/5）：主链与级联副本同口径指向源链（撞名判同源＋链表溯源）
                    copy.setSourceChainId(sourceChain.getId());
                    // 三字段重定向落地：cmpProperty 重定向树、canvasData CHAIN 节点 data.cmpId 同步改写、
                    // EL 一律由生成器产出（copy() 先例，空树不产出 EL）——无直编模式，天然语义等价
                    copy.setCmpProperty(redirectedTree);
                    copy.setCanvasData(ChainForkRedirector.redirectCanvasData(sourceChain.getCanvasData(), codeMapping));
                    copy.setElExpression(redirectedTree == null ? null : expressGenerator.generateEL(redirectedTree).getElStr());
                    chainMapper.insert(copy);
                    if (main) {
                        mainCopyId = copy.getId();
                    }
                }
                codeMapping.put(sourceChain.getChainCode(), newCode);
                DatabusMarketInstallVo.CascadeItemVo cascadeItem = new DatabusMarketInstallVo.CascadeItemVo();
                cascadeItem.setSourceCode(sourceChain.getChainCode());
                cascadeItem.setNewCode(newCode);
                cascadeItem.setSourceName(sourceChain.getChainName());
                cascadeItem.setStatus(cascadeStatus);
                cascade.add(cascadeItem);
            }
            // 主链恒在闭包末位（collectChainClosure 契约：主链最后入列），mainCopyId 必被赋值
            localResourceId = mainCopyId;
            contentHash = chainHash(source);
            mode = MODE_FORK;
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            // 共享注册（§十二-5）：不 fork 副本，localResourceId 直接指向源组件——运行时内存 O(总组件数)，
            // 与安装人数无关；上游更新/停用/下架实时生效，uninstall 仅删安装记录不动本体
            DatabusComponent source = componentMapper.selectById(item.getSourceId());
            if (source == null) {
                throw new ServiceException("源组件不存在或已删除");
            }
            localResourceId = source.getId();
            contentHash = componentHash(source);
            mode = MODE_SHARED;
            componentEnabled = COMPONENT_STATUS_ENABLED.equals(source.getStatus());
            componentCode = source.getComponentCode();
            // 引用挂树（§十二-7）：目录归属落安装记录行，与源组件行的 directory_id 解耦
            installDirectoryId = normalizeDirectoryId(directoryId);
        } else {
            throw new ServiceException("不支持的物料类型：" + item.getResourceType());
        }

        DatabusInstall record = new DatabusInstall();
        record.setOwnerId(userId);
        record.setMarketItemId(itemId);
        record.setLocalResourceId(localResourceId);
        record.setMode(mode);
        // 组件 SHARED：承载引用挂树目录归属（链路 FORK 归属在副本行，此列保持 null 避免双源歧义）
        record.setDirectoryId(installDirectoryId);
        record.setContentHash(contentHash);
        record.setCreateTime(LocalDateTime.now());
        installMapper.insert(record);

        refreshCounts(itemId);

        DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
        vo.setId(record.getId());
        vo.setOwnerId(userId);
        vo.setMarketItemId(itemId);
        vo.setLocalResourceId(localResourceId);
        vo.setMode(mode);
        vo.setDirectoryId(installDirectoryId);
        vo.setComponentCode(componentCode);
        vo.setContentHash(contentHash);
        vo.setCreateTime(record.getCreateTime());
        // 级联清单仅链路 fork 返回（组件 SHARED/连接器 enable 无闭包复制语义）
        vo.setCascade(cascade);
        vo.setResourceType(item.getResourceType());
        vo.setName(item.getName());
        vo.setSummary(item.getSummary());
        vo.setIcon(item.getIcon());
        vo.setColor(item.getColor());
        // 组件共享注册实时看本体启停；链路 fork 为草稿（未发布），无开关
        vo.setEnabled(TYPE_COMPONENT.equals(item.getResourceType()) && Boolean.TRUE.equals(componentEnabled));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(Long resourceId, Long userId) {
        // 连接器无 install 行（安装＝enable 不写台账），走到末尾「未找到安装记录」天然兜底；
        // 其启停/卸载统一由 /databus/connector/defs 端点治理
        DatabusInstall record = installMapper.selectOne(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getLocalResourceId, resourceId)
            .orderByDesc(DatabusInstall::getId)
            .last("limit 1"));
        if (record == null) {
            // 无台账兜底（2026-10-24 级联缺口E）：级联 fork 副本随主链入目录、台账只记主链（§十四-5），
            // 副本自身无安装记录——resourceId 是我名下同源副本时视同卸载直接删副本
            // （不硬拦引用，同下方 §八 软提示口径）
            DatabusChain orphanCopy = chainMapper.selectById(resourceId);
            if (orphanCopy != null && orphanCopy.getSourceChainId() != null
                && Objects.equals(orphanCopy.getCreateBy(), userId)) {
                chainMapper.deleteById(resourceId);
                return true;
            }
            throw new ServiceException("未找到对应的安装记录");
        }
        // 类型：优先查市场物料；源已删时按本地副本落在哪张表推断
        DatabusMarketItem item = null;
        String resourceType = null;
        if (record.getMarketItemId() != null) {
            item = marketItemMapper.selectById(record.getMarketItemId());
            if (item != null) {
                resourceType = item.getResourceType();
            }
        }
        if (resourceType == null) {
            resourceType = inferLocalType(resourceId);
        }
        // 按 mode 分流（§十二-5）：FORK（链路快照 / 历史组件副本）才删本地资源；
        // SHARED 共享注册仅删安装记录，组件本体归原作者与其它安装者共有，卸载不得动
        if (MODE_FORK.equals(record.getMode())) {
            if (TYPE_CHAIN.equals(resourceType)) {
                chainMapper.deleteById(resourceId);
                // 级联回收（2026-10-24 修订 §十四-5「卸载纯删顶层」案）：主链副本删后，
                // 闭包内「未被活链引用且装完未碰（canvas_data 空）」的级联副本一并清
                cascadeCleanClosureCopies(item, userId);
            } else if (TYPE_COMPONENT.equals(resourceType)) {
                componentMapper.deleteById(resourceId);
            }
        }
        installMapper.deleteById(record.getId());
        if (record.getMarketItemId() != null) {
            refreshCounts(record.getMarketItemId());
        }
        // 不硬拦引用：本地链路仍在用该副本由前端给软提示（§八）
        return true;
    }

    /**
     * 卸载主链的级联副本回收（2026-10-24，修订 §十四-5「卸载纯删顶层」）。
     * 候选＝我名下、source_chain_id 落在源链闭包内、装完未进编辑器（canvas_data 仍空）的副本；
     * 再剔「被活链引用」的（主链副本已先删、同批候选互引豁免），剩余逻辑删除。
     * 改造过（进过编辑器保存）/被自建链引用的副本留下——它们是用户资产，回收只清「装完没碰过」的。
     * 源链已删/闭包收集业务异常不阻断卸载：回收是增值动作，静默跳过。
     */
    private void cascadeCleanClosureCopies(DatabusMarketItem item, Long userId) {
        if (item == null || item.getSourceId() == null) {
            return;
        }
        DatabusChain source = chainMapper.selectById(item.getSourceId());
        if (source == null || StringUtils.isBlank(source.getChainCode())) {
            return;
        }
        List<Long> closureSourceIds;
        try {
            closureSourceIds = chainService.collectChainClosure(source.getChainCode()).values().stream()
                .map(DatabusChain::getId).filter(Objects::nonNull).toList();
        } catch (ServiceException e) {
            return;
        }
        if (closureSourceIds.isEmpty()) {
            return;
        }
        List<DatabusChain> copies = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getCreateBy, userId)
            .in(DatabusChain::getSourceChainId, closureSourceIds)
            .isNull(DatabusChain::getCanvasData));
        if (copies.isEmpty()) {
            return;
        }
        Set<String> batchCodes = copies.stream().map(DatabusChain::getChainCode).collect(Collectors.toSet());
        List<Long> deletable = new ArrayList<>();
        for (DatabusChain copy : copies) {
            if (StringUtils.isBlank(copy.getChainCode())) {
                continue;
            }
            if (!isChainReferencedExternally(copy.getChainCode(), batchCodes)) {
                deletable.add(copy.getId());
            }
        }
        if (!deletable.isEmpty()) {
            chainMapper.deleteByIds(deletable);
        }
    }

    /**
     * 链是否被其他活链引用（与 DatabusChainServiceImpl.validateNotChainReferenced 同口径：
     * LIKE 带引号粗筛 → collectChainRefs 精判；逻辑删行 MP 自动排除，excludeCodes 同批互引豁免）。
     */
    private boolean isChainReferencedExternally(String code, Set<String> excludeCodes) {
        List<DatabusChain> candidates = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .like(DatabusChain::getCmpProperty, "\"" + escapeLike(code) + "\""));
        for (DatabusChain candidate : candidates) {
            if (excludeCodes.contains(candidate.getChainCode())) {
                continue;
            }
            if (chainService.collectChainRefs(candidate.getCmpProperty()).contains(code)) {
                return true;
            }
        }
        return false;
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
            vo.setDirectoryId(record.getDirectoryId());
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
                    // SHARED 引用叶子 open 通道：前端按 code 反查台账行
                    vo.setComponentCode(component.getComponentCode());
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

    @Override
    public Boolean moveInstall(MarketInstallMoveBo bo, Long userId) {
        DatabusInstall record = installMapper.selectById(bo.getInstallId());
        if (record == null || !Objects.equals(record.getOwnerId(), userId)) {
            throw new ServiceException("未找到对应的安装记录");
        }
        if (!MODE_SHARED.equals(record.getMode())) {
            throw new ServiceException("仅组件共享安装支持目录归属移动");
        }
        // 引用挂树：只改 install 行组织视角，源组件行 directory_id 不动（显式 set null 支持拖回未归组）
        return installMapper.update(null, Wrappers.<DatabusInstall>lambdaUpdate()
            .eq(DatabusInstall::getId, record.getId())
            .set(DatabusInstall::getDirectoryId, normalizeDirectoryId(bo.getDirectoryId()))) > 0;
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
        // 主链源行（仅 CHAIN 非空）：闭包连带上架要用，方法末尾统一扫
        DatabusChain chainSource = null;
        if (TYPE_CHAIN.equals(resourceType)) {
            DatabusChain source = chainMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源链路不存在或已删除");
            }
            sourceCode = source.getChainCode();
            name = source.getChainName();
            sourceCreateBy = source.getCreateBy();
            chainSource = source;
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
        } else if (TYPE_CONNECTOR.equals(resourceType)) {
            // 连接器（§15.7）：sourceId＝databus_connector_def 行 id；编码用全局唯一的 type，
            // label/icon 取自 def 行 manifest 快照（上传时已校验非空）
            DatabusConnectorDef source = connectorDefMapper.selectById(bo.getSourceId());
            if (source == null) {
                throw new ServiceException("源连接器不存在或已卸载");
            }
            sourceCode = source.getType();
            JsonNode manifest = readManifest(source.getManifestSnapshot());
            name = manifest.path("label").asText(null);
            if (StringUtils.isBlank(name)) {
                name = sourceCode;
            }
            icon = blankToNull(manifest.path("icon").asText(null));
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
        boolean published;
        if (existing != null) {
            // 同源重复提交：更新展示信息并回到待审（清空旧驳回理由与上一轮审核痕迹）
            existing.setName(name);
            existing.setIcon(icon);
            existing.setColor(color);
            existing.setSummary(bo.getSummary());
            existing.setCategoryId(bo.getCategoryId());
            existing.setTags(bo.getTags());
            existing.setModifyNote(bo.getModifyNote());
            existing.setCodeDisplay(codeDisplay);
            existing.setStatus(STATUS_PENDING);
            existing.setRemark("");
            existing.setUpdateTime(now);
            published = marketItemMapper.updateById(existing) > 0;
            // updateById 默认 NOT_NULL，无法把 review_by/review_time 置空 → 显式清审核痕迹
            marketItemMapper.update(null, Wrappers.<DatabusMarketItem>lambdaUpdate()
                .eq(DatabusMarketItem::getId, existing.getId())
                .set(DatabusMarketItem::getReviewBy, null)
                .set(DatabusMarketItem::getReviewTime, null));
        } else {
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
            published = marketItemMapper.insert(add) > 0;
        }
        // 闭包连带上架（2026-10-24 闭包联审）：主链单据落库后扫闭包补依赖单据
        if (published && chainSource != null) {
            autoPublishClosureDeps(chainSource, userId, ownerCode, now);
        }
        return published;
    }

    /**
     * 闭包连带上架（2026-10-24 闭包联审）：publish CHAIN 时随主链扫闭包，同 owner 且市场
     * 尚无单据的 subChain/comp 自动补 PENDING 审核单——治理者从主链审核单入口即可审完整棵树，
     * 不再等作者逐个上架。闭包口径与终审 gate / 审核详情依赖树同源（collectChainClosure ＋
     * ChainForkRedirector.collectComponentCodes）。
     * <p>
     * 跳过规则：① 市场已有该源单据（任何 owner、任何状态）——不重复生成，谁的单据谁审；
     * ② 非本人资源（create_by 空＝系统资源，或属他人）——不越权代上架，终审 gate 照旧报错点名。
     * 连带单 summary/category/tags 留空（未经作者填写），作者可在「我的上架」补信息重提。
     */
    private void autoPublishClosureDeps(DatabusChain root, Long userId, String ownerCode, LocalDateTime now) {
        LinkedHashMap<String, DatabusChain> closure = chainService.collectChainClosure(root.getChainCode());
        for (DatabusChain dep : closure.values()) {
            if (Objects.equals(dep.getId(), root.getId()) || !Objects.equals(dep.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_CHAIN, dep.getId(), dep.getChainCode(), dep.getChainName(),
                null, null, userId, ownerCode, now);
        }
        // 组件维度：闭包全部链（含主链）的组件引用并集（与终审 gate 同口径）
        List<String> componentCodes = new ArrayList<>();
        for (DatabusChain member : closure.values()) {
            for (String code : ChainForkRedirector.collectComponentCodes(member.getCmpProperty())) {
                if (!componentCodes.contains(code)) {
                    componentCodes.add(code);
                }
            }
        }
        if (componentCodes.isEmpty()) {
            return;
        }
        List<DatabusComponent> components = componentMapper.selectList(
            Wrappers.<DatabusComponent>lambdaQuery().in(DatabusComponent::getComponentCode, componentCodes));
        for (DatabusComponent comp : components) {
            if (!Objects.equals(comp.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_COMPONENT, comp.getId(), comp.getComponentCode(), comp.getComponentName(),
                comp.getIcon(), comp.getColor(), userId, ownerCode, now);
        }
    }

    /** 连带补单：市场无该源单据才插 PENDING 行（有单据＝不重复生成，状态维持原样） */
    private void autoPublishDepIfAbsent(String resourceType, Long sourceId, String sourceCode, String name,
                                        String icon, String color, Long userId, String ownerCode, LocalDateTime now) {
        Long exists = marketItemMapper.selectCount(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getResourceType, resourceType)
            .eq(DatabusMarketItem::getSourceId, sourceId));
        if (exists != null && exists > 0) {
            return;
        }
        DatabusMarketItem add = new DatabusMarketItem();
        add.setOwnerId(userId);
        add.setOwnerCode(ownerCode);
        add.setResourceType(resourceType);
        add.setSourceId(sourceId);
        add.setCodeDisplay(ownerCode + DISPLAY_SEPARATOR + sourceCode);
        add.setName(name);
        add.setIcon(icon);
        add.setColor(color);
        add.setStatus(STATUS_PENDING);
        add.setInstallCount(0);
        add.setRefCount(0);
        add.setForkPublishCount(0);
        add.setCreateTime(now);
        add.setUpdateTime(now);
        marketItemMapper.insert(add);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean publishBatch(MarketPublishBatchBo bo, Long userId, String ownerCode) {
        // 批量提审（decisions §十四-6 发布引导弹窗确认后的落库动作）：循环复用单条 publish
        // 的归属校验/同源更新逻辑，整批 all-or-nothing——中途任何一项被拒（如他人资源/保留
        // 分隔符），已处理的项随本事务回滚，弹窗重选后整批重提。
        // 注意 this.publish() 是自调用（不经代理），内层 @Transactional 不生效，由本方法
        // 外层事务统一覆盖，语义即批量原子。
        for (MarketPublishBo item : bo.getItems()) {
            publish(item, userId, ownerCode);
        }
        return true;
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
        // 终审 gate（decisions §十四-7 嵌套审核）：approved 且 CHAIN → 闭包内全部依赖
        // （链＋组件，含系统组件——全量卡，不豁免 create_by 为空的系统资源）市场状态必须 ONLINE。
        // 不满足则拒审：主链上架后若子物料随下架/驳回变悬空，治理者审核通过即背书了一个
        // 装出去必然坏（install 闭包校验拒装）的物料。驳回/非 CHAIN 物料不走 gate。
        if (Boolean.TRUE.equals(bo.getApproved()) && TYPE_CHAIN.equals(item.getResourceType())) {
            validateClosureOnlineForReview(item);
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
            // 通过后清空修改说明（P1 驳回-重提闭环：上架后本轮说明不再有意义）
            update.setModifyNote("");
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
    public List<DatabusMarketItemVo> queryPending(Long userId) {
        // 待审核队列全量返回（含自己上架的项）：治理者需看到全部待审内容，自己上架的项由
        // 前端按 owner 隐藏操作按钮（禁自审 decisions §七-7.1；2026-10-24 用户拍板：
        // 列表要有，只是不给自己上架的项操作权限）
        List<DatabusMarketItem> items = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getStatus, STATUS_PENDING)
            .orderByAsc(DatabusMarketItem::getCreateTime)
            .orderByAsc(DatabusMarketItem::getId));
        return items.stream().map(this::toItemVo).toList();
    }

    @Override
    public DatabusMarketReviewDetailVo reviewDetail(Long id) {
        // 审核详情（GET /databus/market/items/{id}/review-detail，decisions §十四-7 嵌套审核）：
        // 主体 item＋闭包依赖状态树，审核者在终审前先看依赖全貌。闭包收集失败（环/子链悬空）
        // 让 ServiceException 自然抛出——错误文案与 approve gate 一致，审核者看到的拒绝原因
        // 即审批时 gate 会拒的原因。审核界面前端交互分期另启，本期只交付查询端点。
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        DatabusMarketReviewDetailVo vo = new DatabusMarketReviewDetailVo();
        vo.setItem(toItemVo(item));
        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain root = chainMapper.selectById(item.getSourceId());
            // 源链悬空：主体照常返回、依赖空列表——视图不抛错，真正的拒绝发生在 approve gate
            if (root != null) {
                dependencies.addAll(buildReviewDependencies(root, item.getSourceId()));
            }
        }
        vo.setDependencies(dependencies);
        // P1 内容预览（decisions §十四-7 扩展：解「干审」）：CHAIN=画布节点清单，COMPONENT=关键信息
        vo.setPreview(buildReviewPreview(item));
        return vo;
    }

    @Override
    public DatabusMarketPreviewVo preview(Long id) {
        // 装前可看（2026-10-24）：只放行已上架物料——审核中/已驳回/已下架的源实现仍属作者私域，
        // 审核者走 review-detail（另有审核口径），普通浏览者一律拿不到
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
                collectPreviewNodes(chain.getCmpProperty(), nodes, 0);
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
     * 审核内容预览组装（P1，decisions §十四-7 扩展：解「干审」——审核者需见内容概要方能背书）。
     * <p>
     * CHAIN：遍历源链 cmp_property 树，结构关键字（ExpressParserEnum）与业务组件叶子并列，
     * 展示链路骨架（不展开脚本/EL 细节）；组件叶子回填组件行名，查无行退化为编码（悬空）。
     * COMPONENT：作者（create_by 用户名）/版本/脚本语言/描述。
     */
    private DatabusMarketReviewDetailVo.PreviewVo buildReviewPreview(DatabusMarketItem item) {
        DatabusMarketReviewDetailVo.PreviewVo preview = new DatabusMarketReviewDetailVo.PreviewVo();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain chain = chainMapper.selectById(item.getSourceId());
            if (chain == null || chain.getCmpProperty() == null) {
                return preview; // 源链悬空：空预览，gate 在 approve 时拒
            }
            List<DatabusMarketReviewDetailVo.NodeVo> nodes = new ArrayList<>();
            collectPreviewNodes(chain.getCmpProperty(), nodes, 0);
            preview.setNodes(nodes);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            DatabusComponent component = componentMapper.selectById(item.getSourceId());
            if (component == null) {
                return preview;
            }
            // 作者 = 上架者展示码（审核者关心「谁上架的」；组件 create_by 可能是系统资源空值，
            // 不经跨模块用户查询，取 item 侧 ownerCode 即可）
            preview.setAuthor(item.getOwnerCode());
            preview.setCode(component.getComponentCode());
            preview.setVersion(component.getVersion() == null ? null : String.valueOf(component.getVersion()));
            preview.setScriptLang(component.getScriptLang());
            preview.setDescription(component.getDescription());
        }
        return preview;
    }

    /**
     * 递归收集画布节点清单（P1 审核预览 / 2026-10-24 实现预览）：结构关键字（ExpressParserEnum.type，
     * 大小写不敏感）只记节点不记编码；业务组件叶子记 type/编码/名称。遍历含 condition 分支与全部
     * children，与 ChainForkRedirector.collectComponentCodes 同构（口径对齐，防两处清单不一致）。
     * <p>
     * depth 逐级下钻（根 0、children/condition +1），前端按层级缩进渲染成树形，避免平铺一长条。
     */
    private void collectPreviewNodes(CmpProperty node, List<DatabusMarketReviewDetailVo.NodeVo> nodes, int depth) {
        if (node == null) {
            return;
        }
        DatabusMarketReviewDetailVo.NodeVo vo = new DatabusMarketReviewDetailVo.NodeVo();
        vo.setType(node.getType());
        vo.setDepth(depth);
        boolean structure = node.getType() == null
            || Arrays.stream(ExpressParserEnum.values())
            .map(ExpressParserEnum::getType)
            .anyMatch(t -> t.equalsIgnoreCase(node.getType()));
        vo.setStructure(structure);
        if (!structure) {
            String code = StringUtils.isNotBlank(node.getId()) ? node.getId()
                : (node.getProperties() != null ? node.getProperties().getId() : null);
            vo.setCode(code);
            if (StringUtils.isNotBlank(code)) {
                // 组件行名回填（查无行=悬空引用，name 退化为编码展示）
                DatabusComponent component = componentMapper.selectOne(Wrappers.<DatabusComponent>lambdaQuery()
                    .eq(DatabusComponent::getComponentCode, code)
                    .last("limit 1"));
                vo.setName(component == null ? code : component.getComponentName());
            }
        }
        nodes.add(vo);
        collectPreviewNodes(node.getCondition(), nodes, depth + 1);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectPreviewNodes(child, nodes, depth + 1);
            }
        }
    }

    /**
     * 审核详情依赖树组装（decisions §十四-7）：闭包链（主链即受审主体，不重复列出）＋
     * 闭包全部链（含主链）的业务组件叶子，逐项回填市场行状态。与终审 gate、install
     * 闭包校验同一张口径清单——审核者看到的状态树，就是 approve 时 gate 会核对的清单。
     * <p>
     * 2026-10-24 闭包联审：列表从「链先组件后」的平铺改为按引用树先序展开——链的组件
     * 叶子紧贴该链（depth+1），子链逐支下钻；同行列的共享依赖只在首现位置列一次
     * （状态全局化不重复审）。每个 CHAIN 依赖附 blockedBy：其真实闭包内尚未 ONLINE
     * 的成员名（含被去重挪位的共享依赖，口径与 gate 一致）——审核者按行内提示
     * 自下而上审，不再靠 gate 报错试错。
     *
     * @param root        受审链路的源链
     * @param mainChainId 受审链路源 id（闭包内排除自身）
     */
    private List<DatabusMarketReviewDetailVo.DependencyVo> buildReviewDependencies(DatabusChain root, Long mainChainId) {
        LinkedHashMap<String, DatabusChain> closure = chainService.collectChainClosure(root.getChainCode());
        // 市场行一次查齐：闭包全部链 + 全部组件引用（行回填与 blockedBy 判定共用）
        List<Long> closureChainIds = closure.values().stream().map(DatabusChain::getId).toList();
        Map<Long, DatabusMarketItem> chainItemMap = closureChainIds.isEmpty() ? Map.of()
            : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, TYPE_CHAIN)
                    .in(DatabusMarketItem::getSourceId, closureChainIds))
                .stream()
                // 同源 CHAIN item 至多一行（上架侧同源重复提交走更新不新增），合并防御性取首行
                .collect(Collectors.toMap(DatabusMarketItem::getSourceId, it -> it, (a, b) -> a));
        List<String> componentCodes = new ArrayList<>();
        for (DatabusChain member : closure.values()) {
            for (String code : ChainForkRedirector.collectComponentCodes(member.getCmpProperty())) {
                if (!componentCodes.contains(code)) {
                    componentCodes.add(code);
                }
            }
        }
        Map<String, DatabusComponent> componentMap = componentCodes.isEmpty() ? Map.of()
            : componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
                    .in(DatabusComponent::getComponentCode, componentCodes))
                .stream()
                .collect(Collectors.toMap(DatabusComponent::getComponentCode, c -> c, (a, b) -> a));
        List<Long> componentIds = componentMap.values().stream().map(DatabusComponent::getId).toList();
        Map<Long, DatabusMarketItem> componentItemMap = componentIds.isEmpty() ? Map.of()
            : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, TYPE_COMPONENT)
                    .in(DatabusMarketItem::getSourceId, componentIds))
                .stream()
                .collect(Collectors.toMap(DatabusMarketItem::getSourceId, it -> it, (a, b) -> a));

        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        expandReviewDeps(root, 0, closure, chainItemMap, componentMap, componentItemMap,
            new HashSet<>(), new HashSet<>(), dependencies);
        // blockedBy：CHAIN 依赖的真实闭包里未 ONLINE 成员（展示树去重挪位不影响判定口径）
        for (DatabusMarketReviewDetailVo.DependencyVo dep : dependencies) {
            if (!TYPE_CHAIN.equals(dep.getResourceType())) {
                continue;
            }
            DatabusChain sub = closure.get(dep.getCode());
            if (sub != null) {
                dep.setBlockedBy(collectUnonlineDeps(sub, closure, chainItemMap, componentMap, componentItemMap,
                    new HashSet<>(), new ArrayList<>()));
            }
        }
        return dependencies;
    }

    /**
     * 引用树先序展开（2026-10-24 闭包联审）：当前链的组件叶子先紧贴列出（depth+1），
     * 再逐支下钻子链；root 自身是主体不进列表，直接依赖 depth=1。listedXxx 去重——
     * 共享依赖只在首现位置列一次，环引用已由 collectChainClosure 拒绝，这里无需再防。
     */
    private void expandReviewDeps(DatabusChain chain, int depth, Map<String, DatabusChain> closure,
                                  Map<Long, DatabusMarketItem> chainItemMap,
                                  Map<String, DatabusComponent> componentMap,
                                  Map<Long, DatabusMarketItem> componentItemMap,
                                  Set<String> listedChains, Set<String> listedComponents,
                                  List<DatabusMarketReviewDetailVo.DependencyVo> out) {
        for (String code : ChainForkRedirector.collectComponentCodes(chain.getCmpProperty())) {
            if (!listedComponents.add(code)) {
                continue;
            }
            // 悬空引用：查无组件行——sourceId null、name 退化为编码（视图可展示，gate 在 approve 时拒）
            DatabusComponent component = componentMap.get(code);
            DatabusMarketReviewDetailVo.DependencyVo vo = component == null
                ? dependency(TYPE_COMPONENT, null, code, code, null)
                : dependency(TYPE_COMPONENT, component.getId(), component.getComponentCode(),
                    component.getComponentName(), componentItemMap.get(component.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            DatabusChain sub = closure.get(refCode);
            if (sub == null || !listedChains.add(refCode)) {
                continue;
            }
            DatabusMarketReviewDetailVo.DependencyVo vo = dependency(TYPE_CHAIN, sub.getId(), sub.getChainCode(),
                sub.getChainName(), chainItemMap.get(sub.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
            expandReviewDeps(sub, depth + 1, closure, chainItemMap, componentMap, componentItemMap,
                listedChains, listedComponents, out);
        }
    }

    /**
     * 链依赖的前置未过审清单（2026-10-24 闭包联审 blockedBy）：递归该链真实闭包，
     * 收集市场状态非 ONLINE（含未上架/悬空引用）的链与组件名称，返回即「先审谁」。
     * 组件维度不再下钻（组件无闭包）。环引用入列前已被 collectChainClosure 拒绝。
     */
    private List<String> collectUnonlineDeps(DatabusChain chain, Map<String, DatabusChain> closure,
                                             Map<Long, DatabusMarketItem> chainItemMap,
                                             Map<String, DatabusComponent> componentMap,
                                             Map<Long, DatabusMarketItem> componentItemMap,
                                             Set<String> visited, List<String> blocked) {
        for (String code : ChainForkRedirector.collectComponentCodes(chain.getCmpProperty())) {
            DatabusComponent component = componentMap.get(code);
            if (component == null) {
                if (!blocked.contains(code)) {
                    blocked.add(code);
                }
                continue;
            }
            DatabusMarketItem it = componentItemMap.get(component.getId());
            if ((it == null || !STATUS_ONLINE.equals(it.getStatus())) && !blocked.contains(component.getComponentName())) {
                blocked.add(component.getComponentName());
            }
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            if (!visited.add(refCode)) {
                continue;
            }
            DatabusChain sub = closure.get(refCode);
            if (sub == null) {
                blocked.add(refCode);
                continue;
            }
            DatabusMarketItem it = chainItemMap.get(sub.getId());
            if (it == null || !STATUS_ONLINE.equals(it.getStatus())) {
                blocked.add(sub.getChainName());
            }
            collectUnonlineDeps(sub, closure, chainItemMap, componentMap, componentItemMap, visited, blocked);
        }
        return blocked;
    }

    /**
     * 依赖状态条目组装：市场行非空时回填物料 id/状态/审核备注（REJECTED 时 remark 即驳回理由）。
     */
    private DatabusMarketReviewDetailVo.DependencyVo dependency(String resourceType, Long sourceId, String code,
                                                                String name, DatabusMarketItem marketItem) {
        DatabusMarketReviewDetailVo.DependencyVo dep = new DatabusMarketReviewDetailVo.DependencyVo();
        dep.setResourceType(resourceType);
        dep.setSourceId(sourceId);
        dep.setCode(code);
        dep.setName(name);
        if (marketItem != null) {
            dep.setMarketItemId(marketItem.getId());
            dep.setOwnerId(marketItem.getOwnerId());
            dep.setMarketStatus(marketItem.getStatus());
            dep.setRemark(marketItem.getRemark());
            // P1 驳回-重提闭环：依赖项若也在重提中，审核者可看到作者的修改说明
            dep.setModifyNote(marketItem.getModifyNote());
        }
        return dep;
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
     * <p>
     * 优先取安装台账；链路物料无台账时兜底认「我的同源 fork 副本」（2026-10-24 级联缺口A：
     * 级联 fork 随主链入目录、台账只记主链一张，§十四-5——子链卡片不能因此显示「安装」误导，
     * 判据＝我名下未删链 source_chain_id 命中物料源链；组件 SHARED 语义下「安装＝注册」，不兜底）。
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
        // 不做 select 列裁剪（autoResultMap typeHandler 风险，同 validateNotChainReferenced 注释口径）；
        // 同源理论单行（撞名复用 §十四-2），防御取先扫到的
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
     * 物料行中连接器行的 def 映射：market_item.source_id（＝def.id）→ def 行。
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
     * 回填消费侧三字段（installed / localResourceId / mine）。
     * <p>
     * 链路/组件走个人安装台账；连接器走 def 行状态（安装＝enable 全局生效，§15.3），
     * localResourceId 回填 def.id（前端据此匹配 defs 列表取 type 做启停/卸载）。
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

    /**
     * 解析 def 行 manifest 快照（上架取名/图标用；快照坏行按缺省处理交给上游兜底）。
     */
    private JsonNode readManifest(String snapshot) {
        try {
            return objectMapper.readTree(snapshot == null ? "" : snapshot);
        } catch (JacksonException e) {
            throw new ServiceException("连接器 manifest 快照解析失败");
        }
    }

    /**
     * 空白串归一为 null。
     */
    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
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

    /**
     * 撞名同源复用探测（decisions §十四-2）：候选码（prefix+源码截断，与 buildUniqueCode 首选一致）
     * 已存在且 {@code source_chain_id} 指向本源链 → 该行即既有 fork 品，复用不复制；
     * 未撞名或异源（含存量 NULL——存量不迁移，撞名判同源失败）返回 null 走后缀唯一化兜底。
     */
    private DatabusChain findSameSourceCopy(String prefix, DatabusChain sourceChain) {
        String code = sourceChain.getChainCode() == null ? "" : sourceChain.getChainCode();
        String candidate = truncate(prefix + code, CHAIN_CODE_MAX_LEN);
        if (!existsChainCode(candidate)) {
            return null;
        }
        return chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, candidate)
            .eq(DatabusChain::getSourceChainId, sourceChain.getId()));
    }

    /**
     * 安装前闭包市场校验（decisions §十四-4）：闭包内每条链（含主链）都必须在市场存在且 ONLINE。
     * <p>
     * 市场状态是链路安装的依赖闸门——改版冻结（decisions §十四-8：作者改版重发布会把 ONLINE
     * item 置回 PENDING）期间此处即拒装，安装者拿到的永远是过审快照。缺项/非 ONLINE（PENDING/
     * REJECTED/OFFLINE）统一报具体链名，安装者无需自查闭包构成。
     *
     * @param closure 链路安装闭包（含主链，entry 顺序即复制顺序）
     */
    private void validateClosureOnMarket(LinkedHashMap<String, DatabusChain> closure) {
        List<Long> sourceIds = closure.values().stream().map(DatabusChain::getId).toList();
        Map<Long, DatabusMarketItem> itemMap = marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                .eq(DatabusMarketItem::getResourceType, TYPE_CHAIN)
                .in(DatabusMarketItem::getSourceId, sourceIds))
            .stream()
            // 上架归属校验保证「同源 CHAIN item 至多一行」（发布侧同源重复提交走更新不新增），
            // toMap 冲突合并仅防御性取首行
            .collect(Collectors.toMap(DatabusMarketItem::getSourceId, item -> item, (a, b) -> a));
        for (DatabusChain chain : closure.values()) {
            DatabusMarketItem item = itemMap.get(chain.getId());
            if (item == null || !STATUS_ONLINE.equals(item.getStatus())) {
                String state = item == null ? "未上架" : item.getStatus();
                throw new ServiceException("依赖链路'" + chain.getChainName() + "'市场状态为" + state + "，暂不可安装");
            }
        }
    }

    /**
     * 审核终审 gate（decisions §十四-7 嵌套审核）：approve CHAIN 物料前，闭包内全部依赖
     * （链＋组件，含 create_by 为空的系统资源——全量卡）市场状态必须 ONLINE。
     * <p>
     * 与安装侧 {@link #validateClosureOnMarket} 的分工：那边卡的是「装出去的快照此刻可装」，
     * 这边卡的是「治理者通过审核＝背书该物料可装」——主链过审上架后子物料随下架/驳回变
     * 悬空，gate 拒审让治理者先处理依赖而不是放行一个 install 必拒的坏物料。
     * 链依赖按「未上架/未过审」两段报错，组件依赖多一段「已不存在」（悬空引用）。
     *
     * @param item 受审 CHAIN 物料行（approve=true 分支进入）
     */
    private void validateClosureOnlineForReview(DatabusMarketItem item) {
        DatabusChain root = chainMapper.selectById(item.getSourceId());
        if (root == null) {
            throw new ServiceException("源链路不存在或已删除，无法审核通过");
        }
        LinkedHashMap<String, DatabusChain> closure = chainService.collectChainClosure(root.getChainCode());
        // 链维度：主链即受审 item 本身（PENDING 态），不算依赖——否则自相矛盾
        List<Long> chainIds = closure.values().stream()
            .map(DatabusChain::getId)
            .filter(depId -> !Objects.equals(depId, item.getSourceId()))
            .toList();
        Map<Long, DatabusMarketItem> chainItemMap = chainIds.isEmpty() ? Map.of()
            : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, TYPE_CHAIN)
                    .in(DatabusMarketItem::getSourceId, chainIds))
                .stream()
                .collect(Collectors.toMap(DatabusMarketItem::getSourceId, it -> it, (a, b) -> a));
        for (DatabusChain dep : closure.values()) {
            if (Objects.equals(dep.getId(), item.getSourceId())) {
                continue;
            }
            DatabusMarketItem depItem = chainItemMap.get(dep.getId());
            if (depItem == null) {
                throw new ServiceException("依赖链路'" + dep.getChainName() + "'未上架市场，请先处理");
            }
            if (!STATUS_ONLINE.equals(depItem.getStatus())) {
                throw new ServiceException("依赖链路'" + dep.getChainName() + "'未过审（市场状态：" + depItem.getStatus() + "），请先处理");
            }
        }
        // 组件维度：闭包全部链（含主链）的组件引用并集——悬空引用（组件行已删）直接拒
        List<String> componentCodes = new ArrayList<>();
        for (DatabusChain member : closure.values()) {
            for (String code : ChainForkRedirector.collectComponentCodes(member.getCmpProperty())) {
                if (!componentCodes.contains(code)) {
                    componentCodes.add(code);
                }
            }
        }
        if (!componentCodes.isEmpty()) {
            Map<String, DatabusComponent> componentMap = componentMapper.selectList(
                    Wrappers.<DatabusComponent>lambdaQuery()
                        .in(DatabusComponent::getComponentCode, componentCodes))
                .stream()
                .collect(Collectors.toMap(DatabusComponent::getComponentCode, c -> c, (a, b) -> a));
            List<Long> componentIds = componentMap.values().stream().map(DatabusComponent::getId).toList();
            Map<Long, DatabusMarketItem> componentItemMap = componentIds.isEmpty() ? Map.of()
                : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                        .eq(DatabusMarketItem::getResourceType, TYPE_COMPONENT)
                        .in(DatabusMarketItem::getSourceId, componentIds))
                    .stream()
                    .collect(Collectors.toMap(DatabusMarketItem::getSourceId, it -> it, (a, b) -> a));
            for (String code : componentCodes) {
                DatabusComponent component = componentMap.get(code);
                if (component == null) {
                    throw new ServiceException("依赖组件'" + code + "'已不存在，请先处理");
                }
                DatabusMarketItem depItem = componentItemMap.get(component.getId());
                if (depItem == null) {
                    throw new ServiceException("依赖组件'" + component.getComponentName() + "'未上架市场，请先处理");
                }
                if (!STATUS_ONLINE.equals(depItem.getStatus())) {
                    throw new ServiceException("依赖组件'" + component.getComponentName() + "'未过审（市场状态：" + depItem.getStatus() + "），请先处理");
                }
            }
        }
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
        vo.setModifyNote(item.getModifyNote());
        vo.setReviewBy(item.getReviewBy());
        vo.setReviewTime(item.getReviewTime());
        return vo;
    }

}