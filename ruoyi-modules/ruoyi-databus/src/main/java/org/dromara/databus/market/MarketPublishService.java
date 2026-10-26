package org.dromara.databus.market;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketPublishBatchBo;
import org.dromara.databus.domain.bo.MarketPublishBo;
import org.dromara.databus.domain.bo.MarketReviewBo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.ChainForkRedirector;
import org.dromara.databus.market.support.MarketClosure;
import org.dromara.databus.market.support.MarketClosureLoader;
import org.dromara.databus.market.support.MarketResourceTypes;
import org.dromara.databus.market.support.MarketStates;
import org.dromara.databus.market.support.MarketVoAssembler;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 物料市场——上架与审核（设计文档 databus-material-market.md §七、§十，decisions §十四-6/7/8）。
 * <p>
 * 管五件事：提交上架（新建一条待审行；同一个资源重复提交就把原来那行改回待审）、
 * 批量提交审核、下架、审核通过或驳回、删除上架行（只有作者本人能删）。
 * <p>
 * 有两条规则要同时看好几种资源，都写在本类：
 * <ul>
 *   <li>顺带补审核单：提交一条链路审核时，把它的依赖清单一起扫一遍，凡是同一个人所有、
 *       市场里还没有审核单的子链和组件，自动补一条待审行。这样审核人从主链这一条
 *       审核单就能把整棵依赖树审完，不用等作者一个一个上架；</li>
 *   <li>通过审核前先查依赖：审核通过一条链路前，依赖清单里的全部依赖（链和组件，
 *       系统资源也算）市场状态必须都是已上线，否则不给通过。因为这种物料就算通过了，
 *       别人安装时也会被安装侧的检查拒掉，通过了没意义。</li>
 * </ul>
 * 两条规则用的都是 {@link MarketClosureLoader} 查出来的同一份清单，和安装侧的判断标准一致。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketPublishService {

    private static final String TYPE_COMPONENT = MarketResourceTypes.COMPONENT;
    private static final String TYPE_CHAIN = MarketResourceTypes.CHAIN;

    /**
     * 资源类型：连接器（§15.7：sourceId＝databus_connector_def 行 id，编码用全局唯一的 type）
     */
    private static final String TYPE_CONNECTOR = MarketResourceTypes.CONNECTOR;

    private static final String STATUS_PENDING = MarketStates.STATUS_PENDING;
    private static final String STATUS_ONLINE = MarketStates.STATUS_ONLINE;
    private static final String STATUS_OFFLINE = MarketStates.STATUS_OFFLINE;
    private static final String STATUS_REJECTED = MarketStates.STATUS_REJECTED;
    private static final String CODE_SEPARATOR = MarketStates.CODE_SEPARATOR;
    private static final String DISPLAY_SEPARATOR = MarketStates.DISPLAY_SEPARATOR;

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * manifest 快照解析用的 Jackson3 mapper（构造注入容器里的 bean：平台是 Spring Boot4/Jackson3，
     * 容器提供的是 tools.jackson 的 ObjectMapper/JsonMapper，带着全局的序列化设置）
     */
    private final ObjectMapper objectMapper;

    /**
     * 链路服务：审核详情展开依赖树时，用它取子链引用了哪些链
     */
    private final IDatabusChainService chainService;

    /**
     * 查依赖清单：顺带补审核单、通过审核前查依赖、审核详情依赖树，三处共用同一份清单
     */
    private final MarketClosureLoader closureLoader;

    private final MarketVoAssembler assembler;

    /**
     * 提交上架：新建一条待审物料行；如果这个资源以前上架过，就把原来那行的展示信息改掉、
     * 状态回到待审。提交链路时还会顺带给同一个人的依赖补审核单。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean publish(MarketPublishBo bo, Long userId, String ownerCode) {
        String resourceType = bo.getResourceType();
        String sourceCode;
        String name;
        String icon = null;
        String color = null;
        Long sourceCreateBy;
        // 主链的源行（只有 CHAIN 才不为空）：方法末尾要按它查依赖清单补审核单，先存下来
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
            // 连接器（§15.7）：sourceId 就是 databus_connector_def 的行 id；编码用它全局唯一的 type，
            // 名字和图标从 def 行存的 manifest 快照里取（上传的时候已经校验过不为空）
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
        // 检查有没有权限上架（§七 7.1）：系统资源（create_by 为空）只有带审核权限的人能上架，
        // 其余资源只有作者本人能上架
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
            // 同一个资源重复提交：改掉展示信息、状态回到待审，顺便清掉上一次的驳回理由和审核记录
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
            // updateById 默认忽略 null 字段，没法把 review_by/review_time 置空 → 单独发一条 update 清空
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
        // 顺带补审核单（2026-10-24 定的）：主链的审核单写库后，再按依赖清单补其他审核单
        if (published && chainSource != null) {
            autoPublishClosureDeps(chainSource, userId, ownerCode, now);
        }
        return published;
    }

    /**
     * 顺带补审核单（2026-10-24 定的）：提交一条链路审核时，把它的依赖清单也扫一遍，
     * 凡是同一个人所有、市场里还没有审核单的子链和组件，自动补一条待审（PENDING）审核单。
     * 这样审核人从主链这一条审核单就能把整棵依赖树审完，不用等作者一个一个上架。
     * 这里查的依赖清单和「通过审核前查依赖」「审核详情依赖树」是同一份
     * （collectChainClosure ＋ ChainForkRedirector.collectComponentCodes）。
     * <p>
     * 两种情况跳过不补：① 市场里已经有这个资源的审核单了（不管是谁的、什么状态）——
     * 不重复生成，谁的单子谁自己审；② 不是本人的资源（create_by 为空＝系统资源，或者属于别人）——
     * 不替别人上架，这种依赖到审核通过那一步会照常报错并说出具体名字。
     * 自动补的单子 summary/category/tags 留空（作者没填过），作者可以在「我的上架」里补完再重新提交。
     */
    private void autoPublishClosureDeps(DatabusChain root, Long userId, String ownerCode, LocalDateTime now) {
        MarketClosure closure = closureLoader.load(root.getChainCode());
        for (DatabusChain dep : closure.chains().values()) {
            if (Objects.equals(dep.getId(), root.getId()) || !Objects.equals(dep.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_CHAIN, dep.getId(), dep.getChainCode(), dep.getChainName(),
                null, null, userId, ownerCode, now);
        }
        // 组件这一边：清单里全部链（含主链）引用的组件合在一起去重（和审核通过前的检查用同一份）。
        // 如果某个组件编码在清单里、但组件行已经被删了，这里查不到自然就跳过，
        // 留给审核通过那一步去报错并说出具体名字
        for (DatabusComponent comp : closure.components().values()) {
            if (!Objects.equals(comp.getCreateBy(), userId)) {
                continue;
            }
            autoPublishDepIfAbsent(TYPE_COMPONENT, comp.getId(), comp.getComponentCode(), comp.getComponentName(),
                comp.getIcon(), comp.getColor(), userId, ownerCode, now);
        }
    }

    /** 补一条待审行：只有市场里还没有这个资源的审核单时才插（已有就不重复生成，原状态不动） */
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
     * 审核：通过就置为上线，驳回就置为驳回并记下理由。不许审自己上架的物料；
     * 通过一条链路前还要先查它的依赖是否都已上线。
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
        // （链和组件，系统组件也算——全部都查，create_by 为空的系统资源不例外）市场状态必须是 ONLINE。
        // 不满足就不给通过：主链上架以后，如果子物料被别人下架或驳回、变得查不到了，
        // 审核人这时候点通过就等于确认了一个「装出去必然坏」的物料（别人安装时会被依赖检查拒掉）。
        // 驳回、以及不是 CHAIN 的物料，都不做这个检查
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
            // 通过后清空修改说明（这段说明是作者被驳回后重提时写的，物料上线后就没用了）
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
        // 已装副本不受影响：安装时存的是内容快照，不依赖市场这一行。
        // 安装记录行也不删，市场上架行查不到时由 queryInstalled 按「已下架」显示。
        return marketItemMapper.deleteById(id) > 0;
    }

    /**
     * 审核详情：物料本身 ＋ 它依赖了哪些东西及各自的市场状态 ＋ 内容预览。
     */
    public DatabusMarketReviewDetailVo reviewDetail(Long id) {
        // 审核详情（GET /databus/market/items/{id}/review-detail，decisions §十四-7）：
        // 让审核人在点通过之前先看清依赖情况。
        // 查依赖清单失败时（有环、或子链查不到），直接把 ServiceException 抛出去不接——
        // 这样审核人看到的报错，和他点通过时会被拒的报错是同一句，不用来回试。
        // 本期只提供这个查询接口，审核界面的前端交互另排期做。
        DatabusMarketItem item = marketItemMapper.selectById(id);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        DatabusMarketReviewDetailVo vo = new DatabusMarketReviewDetailVo();
        vo.setItem(assembler.toItemVo(item));
        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain root = chainMapper.selectById(item.getSourceId());
            // 源链已被删：物料本身照常返回、依赖给空列表，这里不报错。
            // 真正的拒绝发生在点「通过」的时候。
            if (root != null) {
                dependencies.addAll(buildReviewDependencies(root));
            }
        }
        vo.setDependencies(dependencies);
        // 内容预览（decisions §十四-7）：链路给画布节点清单，组件给关键信息
        vo.setPreview(buildReviewPreview(item));
        return vo;
    }

    /**
     * 拼审核用的内容预览（decisions §十四-7）：让审核人看到物料里面大概是什么，
     * 不用只看个标题就通过。
     * <p>
     * 链路：把源链的画布配置树走一遍，控制结构（ExpressParserEnum 里的关键字）和
     * 业务组件并列列出，只展示整条链的骨架，不展开脚本和 EL 的细节。
     * 组件那一行会去查组件名，组件已被删就直接显示编码。
     * 组件：作者（create_by 对应的用户名）、版本、脚本语言、描述。
     */
    private DatabusMarketReviewDetailVo.PreviewVo buildReviewPreview(DatabusMarketItem item) {
        DatabusMarketReviewDetailVo.PreviewVo preview = new DatabusMarketReviewDetailVo.PreviewVo();
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            DatabusChain chain = chainMapper.selectById(item.getSourceId());
            if (chain == null || chain.getCmpProperty() == null) {
                return preview; // 源链已被删：给个空预览，点通过时会被拒
            }
            List<DatabusMarketReviewDetailVo.NodeVo> nodes = new ArrayList<>();
            assembler.collectPreviewNodes(chain.getCmpProperty(), nodes, 0);
            preview.setNodes(nodes);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            DatabusComponent component = componentMapper.selectById(item.getSourceId());
            if (component == null) {
                return preview;
            }
            // 作者这里填上架人的展示码，不填组件的 create_by：审核人想知道的是「谁上架的」，
            // 而系统资源类组件的 create_by 是空的，跨模块去查用户也没必要。
            preview.setAuthor(item.getOwnerCode());
            preview.setCode(component.getComponentCode());
            preview.setVersion(component.getVersion() == null ? null : String.valueOf(component.getVersion()));
            preview.setScriptLang(component.getScriptLang());
            preview.setDescription(component.getDescription());
        }
        return preview;
    }

    /**
     * 拼审核详情的依赖列表（decisions §十四-7）：把依赖清单里的链（正在审的这条主链不重复列）
     * 和所有链引用的业务组件都列出来，每一项都去查它在市场里的状态。
     * 这份清单和「通过审核前查依赖」「安装时查依赖」用的是同一份，
     * 所以审核人在这里看到的状态，就是点通过时程序会核对的那些。
     * <p>
     * 2026-10-24 定的排列方式：不再「先列所有链、再列所有组件」，改成按引用关系一层层展开——
     * 一条链引用的组件紧跟在这条链下面（depth+1），它引用的子链再往下展开。
     * 被多处共用的依赖只在第一次出现的地方列一次，状态是全局的，不用重复审。
     * 每条链依赖还带一个 blockedBy：把它自己依赖的东西里市场状态还不是 ONLINE 的名字列出来
     * （被挪了位置的共用依赖也算进去，判断标准和点通过时一致）——
     * 审核人照着这个提示从最底层的先审，不用靠程序报错来试。
     *
     * @param root 正在审的这条链路的源链（它自己是审核对象，不进列表，所以不用再传 id 去排除）
     */
    private List<DatabusMarketReviewDetailVo.DependencyVo> buildReviewDependencies(DatabusChain root) {
        MarketClosure closure = closureLoader.load(root.getChainCode());
        List<DatabusMarketReviewDetailVo.DependencyVo> dependencies = new ArrayList<>();
        expandReviewDeps(root, 0, closure, new HashSet<>(), new HashSet<>(), dependencies);
        // blockedBy：这条链自己依赖的东西里，市场状态还不是 ONLINE 的那些。
        // 上面展示列表做了去重挪位，但这里算的是它真实的依赖，不受挪位影响。
        for (DatabusMarketReviewDetailVo.DependencyVo dep : dependencies) {
            if (!TYPE_CHAIN.equals(dep.getResourceType())) {
                continue;
            }
            DatabusChain sub = closure.chains().get(dep.getCode());
            if (sub != null) {
                dep.setBlockedBy(collectUnonlineDeps(sub, closure, new HashSet<>(), new ArrayList<>()));
            }
        }
        return dependencies;
    }

    /**
     * 按引用关系一层层展开（2026-10-24 定的）：先把当前链引用的组件紧跟列出来（depth+1），
     * 再挨个展开它引用的子链。root 自己是审核对象不进列表，它的直接依赖 depth=1。
     * listedChains / listedComponents 用来去重——共用的依赖只在第一次出现的地方列一次。
     * 环引用在 collectChainClosure 那一步就已经被拒了，这里不用再防。
     */
    private void expandReviewDeps(DatabusChain chain, int depth, MarketClosure closure,
                                  Set<String> listedChains, Set<String> listedComponents,
                                  List<DatabusMarketReviewDetailVo.DependencyVo> out) {
        for (String code : ChainForkRedirector.collectComponentCodes(chain.getCmpProperty())) {
            if (!listedComponents.add(code)) {
                continue;
            }
            // 组件已被删：sourceId 给 null、名字直接用编码。列表照常展示，点通过时会被拒
            DatabusComponent component = closure.components().get(code);
            DatabusMarketReviewDetailVo.DependencyVo vo = component == null
                ? dependency(TYPE_COMPONENT, null, code, code, null)
                : dependency(TYPE_COMPONENT, component.getId(), component.getComponentCode(),
                    component.getComponentName(), closure.componentItems().get(component.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            DatabusChain sub = closure.chains().get(refCode);
            if (sub == null || !listedChains.add(refCode)) {
                continue;
            }
            DatabusMarketReviewDetailVo.DependencyVo vo = dependency(TYPE_CHAIN, sub.getId(), sub.getChainCode(),
                sub.getChainName(), closure.chainItems().get(sub.getId()));
            vo.setDepth(depth + 1);
            out.add(vo);
            expandReviewDeps(sub, depth + 1, closure, listedChains, listedComponents, out);
        }
    }

    /**
     * 查这条链还差哪些依赖没上线（2026-10-24 定的，对应 blockedBy 字段）：
     * 递归走一遍它真实的依赖清单，把市场状态不是 ONLINE 的链和组件名字收进来
     * （没上架的、已被删查不到的都算）。返回的就是「该先审谁」。
     * 组件没有依赖清单，所以组件这一层不再往下走。
     * 环引用在 collectChainClosure 那一步就已经被拒了。
     */
    private List<String> collectUnonlineDeps(DatabusChain chain, MarketClosure closure,
                                             Set<String> visited, List<String> blocked) {
        for (String code : ChainForkRedirector.collectComponentCodes(chain.getCmpProperty())) {
            DatabusComponent component = closure.components().get(code);
            if (component == null) {
                if (!blocked.contains(code)) {
                    blocked.add(code);
                }
                continue;
            }
            DatabusMarketItem it = closure.componentItems().get(component.getId());
            if ((it == null || !STATUS_ONLINE.equals(it.getStatus())) && !blocked.contains(component.getComponentName())) {
                blocked.add(component.getComponentName());
            }
        }
        for (String refCode : chainService.collectChainRefs(chain.getCmpProperty())) {
            if (!visited.add(refCode)) {
                continue;
            }
            DatabusChain sub = closure.chains().get(refCode);
            if (sub == null) {
                blocked.add(refCode);
                continue;
            }
            DatabusMarketItem it = closure.chainItems().get(sub.getId());
            if (it == null || !STATUS_ONLINE.equals(it.getStatus())) {
                blocked.add(sub.getChainName());
            }
            collectUnonlineDeps(sub, closure, visited, blocked);
        }
        return blocked;
    }

    /**
     * 拼一条依赖记录：市场上架行查得到时，把物料 id、状态、审核备注填进去
     * （状态是 REJECTED 时，备注里就是驳回理由）。
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
            // 依赖项如果也是被驳回后重提的，审核人在这里能看到作者写的修改说明
            dep.setModifyNote(marketItem.getModifyNote());
        }
        return dep;
    }

    /**
     * 通过审核前先查依赖（decisions §十四-7）：一条链路要审核通过，它依赖清单里的
     * 全部依赖（链和组件都算，create_by 为空的系统资源也算，一个都不放过）
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

    /**
     * 解析连接器 def 行里存的 manifest 内容（上架时取名字和图标用）。解析不出来直接报错。
     */
    private JsonNode readManifest(String snapshot) {
        try {
            return objectMapper.readTree(snapshot == null ? "" : snapshot);
        } catch (JacksonException e) {
            throw new ServiceException("连接器 manifest 快照解析失败");
        }
    }

    /**
     * 空串或纯空格统一转成 null。
     */
    private String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }
}
