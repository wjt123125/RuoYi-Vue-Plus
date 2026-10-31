package org.dromara.databus.market;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketInstallMoveBo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.ChainForkRedirector;
import org.dromara.databus.market.support.MarketClosure;
import org.dromara.databus.market.support.MarketClosureLoader;
import org.dromara.databus.market.support.MarketResourceTypes;
import org.dromara.databus.market.support.MarketStates;
import org.dromara.databus.market.support.MarketText;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 物料市场——安装与卸载（设计文档 databus-material-market.md §五~§六、§十二，decisions §十四~§十五）。
 * <p>
 * 三类物料装法不一样：
 * <ul>
 *   <li>链路＝复制一份自己的副本：主链和它引用的全部子链（包括子链再引用的）一条条复制出来，
 *       每条都是独立的行，都放在「我的」目录最外层；安装记录只记主链这一条，
 *       跟着复制出来的子链副本靠 source_chain_id 能查到是从哪条链复制来的；</li>
 *   <li>组件＝大家一起用同一个：不复制副本，localResourceId 直接指向源组件，作者改了两边马上都生效；</li>
 *   <li>连接器＝启用：把 def 行状态改成 ENABLED，全局生效，不复制也不写安装记录（§15.3）。</li>
 * </ul>
 * 卸载按 mode 分开处理：FORK 删本地副本，顺带把「装完之后一直没动过」的子链副本一起删；
 * SHARED 只删安装记录，组件本体不动。
 * <p>
 * 编码规则：链路副本的编码统一加 {@code u{ownerId}__} 前缀（双下划线是系统保留的分隔符，
 * 源编码里不会有 {@code __}，所以不同人的副本编码不会撞）。
 * install/ref/forkPublish 这三个计数列是缓存值，安装和卸载之后同步刷新（§九：不放在实时查询里算）——
 * 全项目只有本类会改这三列，所以刷新逻辑也写在这里。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketInstallService {

    private static final String TYPE_COMPONENT = MarketResourceTypes.COMPONENT;
    private static final String TYPE_CHAIN = MarketResourceTypes.CHAIN;

    /**
     * 资源类型：连接器（§15.3「什么都能装」：装连接器就是把 def 行启用，
     * 不复制副本，也不写安装记录）
     */
    private static final String TYPE_CONNECTOR = MarketResourceTypes.CONNECTOR;

    private static final String STATUS_ONLINE = MarketStates.STATUS_ONLINE;

    /**
     * 安装模式：复制副本（只有装链路用）
     */
    private static final String MODE_FORK = MarketStates.MODE_FORK;

    /**
     * 安装模式：大家一起用同一个（只有装组件用：不复制副本，localResourceId 指向源组件，
     * 作者更新或停用、物料下架都马上生效）
     */
    private static final String MODE_SHARED = MarketStates.MODE_SHARED;

    private static final String CHAIN_STATUS_DRAFT = MarketStates.CHAIN_STATUS_DRAFT;
    private static final String CHAIN_STATUS_PUBLISHED = MarketStates.CHAIN_STATUS_PUBLISHED;
    private static final String COMPONENT_STATUS_ENABLED = MarketStates.COMPONENT_STATUS_ENABLED;
    private static final String DEF_STATUS_ENABLED = MarketStates.DEF_STATUS_ENABLED;
    private static final String TEMPLATE_FLAG_NO = MarketStates.TEMPLATE_FLAG_NO;
    private static final String TEMPLATE_FLAG_YES = MarketStates.TEMPLATE_FLAG_YES;
    private static final String CODE_SEPARATOR = MarketStates.CODE_SEPARATOR;
    private static final String HASH_SEP = MarketStates.HASH_SEP;
    private static final String DISPLAY_SEPARATOR = MarketStates.DISPLAY_SEPARATOR;
    private static final int CHAIN_CODE_MAX_LEN = MarketStates.CHAIN_CODE_MAX_LEN;
    private static final int DRAFT_VERSION = MarketStates.DRAFT_VERSION;
    private static final int CODE_SEQ_MAX = MarketStates.CODE_SEQ_MAX;
    private static final String CASCADE_NEW = MarketStates.CASCADE_NEW;
    private static final String CASCADE_REUSED = MarketStates.CASCADE_REUSED;

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 链路服务：查依赖清单，以及删链的唯一入口（里面会做引用检查、清 Rule-DB 的 lf_chain、清安装记录）
     */
    private final IDatabusChainService chainService;

    /**
     * EL 生成器：副本的 elExpression 一律拿重定向之后的组件树重新生成一遍
     * （decisions §十四-3，跟 copy() 的做法一样——项目里没有手写 EL 这种模式，
     * 生成器产出的 EL 和原来的意思是一样的）
     */
    private final ExpressGenerator expressGenerator;

    /**
     * 查依赖清单：安装前用它检查这些依赖在市场里的状态
     */
    private final MarketClosureLoader closureLoader;

    /**
     * 安装物料：链路复制一份副本 / 组件大家一起用同一个 / 连接器启用。
     */
    @Transactional(rollbackFor = Exception.class)
    public DatabusMarketInstallVo install(Long itemId, Long directoryId, Long userId) {
        DatabusMarketItem item = marketItemMapper.selectById(itemId);
        if (item == null) {
            throw new ServiceException("物料不存在或已删除");
        }
        if (!STATUS_ONLINE.equals(item.getStatus())) {
            throw new ServiceException("物料未上线，暂不可安装");
        }

        // 装连接器＝把 def 行置为启用：不复制副本、不写安装记录，做完就返回（§15.3）
        if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            return enableConnector(item, userId);
        }
        assertNotInstalled(itemId, userId);

        InstallOutcome outcome;
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            outcome = forkChainClosure(item, directoryId, userId);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            outcome = shareComponent(item, directoryId);
        } else {
            throw new ServiceException("不支持的物料类型：" + item.getResourceType());
        }

        DatabusInstall record = saveInstallRecord(itemId, userId, outcome);
        refreshCounts(itemId);
        return toInstallVo(record, item, userId, outcome);
    }

    /**
     * install 各分支的产物：链路复制副本和组件共用同一个源，两条路要写库的字段一次性都放在这里，
     * {@link #saveInstallRecord} 和 {@link #toInstallVo} 共用（连接器不写安装记录，不走这里）
     *
     * @param localResourceId  本地资源 id——链路＝主链副本的行 id，组件＝源组件 id
     * @param mode             FORK / SHARED
     * @param contentHash      安装时按源内容算出的一个标识值，卸载和改版时用它比对内容有没有变
     * @param directoryId      安装记录行放在哪个目录，只有组件安装填（链路的目录记在副本行上）
     * @param componentCode    组件编码，只有装组件时填
     * @param componentEnabled 源组件现在是不是启用状态，只有装组件时填
     * @param cascade          链路跟着复制出来的清单，只有装链路时填
     */
    private record InstallOutcome(Long localResourceId, String mode, String contentHash, Long directoryId,
                                  String componentCode, Boolean componentEnabled,
                                  List<DatabusMarketInstallVo.CascadeItemVo> cascade) {
    }

    /**
     * 装连接器（§15.3）：就是把 def 行置为启用——不复制副本、不用选目录、不写安装记录
     * （安装记录只记链路和组件）。启用/停用/卸载都走 /databus/connector/defs 那几个接口
     */
    private DatabusMarketInstallVo enableConnector(DatabusMarketItem item, Long userId) {
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
        // install_count 是安装记录的条数缓存，连接器不写安装记录，所以不刷（§15.3）
        DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
        vo.setOwnerId(userId);
        vo.setMarketItemId(item.getId());
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

    /**
     * 拦住重复安装：同一个人对同一个物料只能有一条安装记录
     */
    private void assertNotInstalled(Long itemId, Long userId) {
        Long installed = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getMarketItemId, itemId));
        if (installed != null && installed > 0) {
            throw new ServiceException("你已安装该物料，无需重复安装");
        }
    }

    /**
     * 装链路＝复制一份副本（decisions §十四-1）：主链和它引用的全部子链（包括子链再引用的）
     * 一条条复制出来，每条都是独立的行，都放在「我的」目录最外层，
     * 编码加 u{ownerId}__ 前缀避免和别人的撞（目录和前缀只有装链路时才用）
     */
    private InstallOutcome forkChainClosure(DatabusMarketItem item, Long directoryId, Long userId) {
        Long targetDirectoryId = normalizeDirectoryId(directoryId);
        String prefix = namespacePrefix(userId);
        DatabusChain source = chainMapper.selectById(item.getSourceId());
        if (source == null) {
            throw new ServiceException("源链路不存在或已删除");
        }
        // 复制前先做两件事：查依赖清单（顺序是从被引用的到引用它的）＋检查市场状态
        // （§十四-4：清单里所有链都必须是 ONLINE。作者改版重新提交审核这段时间就不给装，
        // 这样安装者拿到的永远是整条链都过了审的那一份）
        MarketClosure closure = closureLoader.load(source.getChainCode());
        validateClosureOnMarket(closure);
        // 按「先复制被引用的、再复制引用它的」这个顺序一条条复制：这样改链引用的时候，
        // 子链的新编码已经知道了。靠「原编码→新编码」这张对应表改三个地方
        // （cmpProperty 里的链节点、canvasData 里的 data.cmpId、EL 重新生成）
        Map<String, String> codeMapping = new HashMap<>();
        List<DatabusMarketInstallVo.CascadeItemVo> cascade = new ArrayList<>(closure.chains().size());
        Long mainCopyId = null;
        for (DatabusChain sourceChain : closure.chains().values()) {
            boolean main = Objects.equals(sourceChain.getId(), source.getId());
            DatabusChain existing = findReusableSameSourceCopy(userId, sourceChain);
            String newCode;
            String cascadeStatus;
            if (existing != null) {
                // 能复用就不重复复制（decisions §十四-2、§十五-6）：我名下已经有一条
                // 从同一条源链复制来的、装完之后一直没动过的副本（重复点安装、
                // 或者这个副本在装别的物料时已经复制过了）→ 直接用那一行，不再复制
                newCode = existing.getChainCode();
                cascadeStatus = CASCADE_REUSED;
                if (main) {
                    mainCopyId = existing.getId();
                }
            } else {
                // 没有能复用的：第一次装，或者已有的副本被改过、被标成了精选模板，
                // 或者老数据的 source_chain_id 是 NULL 认不出是不是同一条源链
                // → 从市场原版复制一份，编码加后缀避开已有的副本
                newCode = buildUniqueCode(prefix, sourceChain.getChainCode(), CHAIN_CODE_MAX_LEN, this::existsChainCode);
                cascadeStatus = CASCADE_NEW;
                DatabusChain copy = copyChain(sourceChain, newCode, targetDirectoryId, codeMapping);
                chainMapper.insert(copy);
                if (main) {
                    mainCopyId = copy.getId();
                }
            }
            codeMapping.put(sourceChain.getChainCode(), newCode);
            cascade.add(cascadeItem(sourceChain, newCode, cascadeStatus));
        }
        // 主链一定在清单的最后一个（collectChainClosure 就是这么排的），所以 mainCopyId 一定有值
        return new InstallOutcome(mainCopyId, MODE_FORK, chainHash(source), null, null, null, cascade);
    }

    /**
     * 复制一条链（decisions §十四-3）：把它引用的链编码改成副本的新编码，
     * 副本不会指回原来的链。三个字段一起写——cmpProperty 改成指向副本的树、
     * canvasData 里链节点的 data.cmpId 同步改、EL 一律交给生成器重新产出
     * （和 copy() 一样，组件树为空就不产 EL）——项目里没有手写 EL 这种模式，
     * 生成出来的 EL 和原来意思一样
     */
    private DatabusChain copyChain(DatabusChain sourceChain, String newCode, Long targetDirectoryId,
                                   Map<String, String> codeMapping) {
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
        // 记下是从哪条源链复制来的（decisions §十四-2/5）：主链副本和跟着复制的子链副本
        // 都填 source_chain_id（靠编码认不出来的老数据就靠这个字段认）
        copy.setSourceChainId(sourceChain.getId());
        copy.setCmpProperty(redirectedTree);
        copy.setCanvasData(ChainForkRedirector.redirectCanvasData(sourceChain.getCanvasData(), codeMapping));
        copy.setElExpression(redirectedTree == null ? null : expressGenerator.generateEL(redirectedTree).getElStr());
        return copy;
    }

    /**
     * 复制清单里的一项：源编码→新编码，以及这一条是 NEW（新复制的）还是 REUSED（复用了已有副本）
     */
    private DatabusMarketInstallVo.CascadeItemVo cascadeItem(DatabusChain sourceChain, String newCode, String status) {
        DatabusMarketInstallVo.CascadeItemVo cascadeItem = new DatabusMarketInstallVo.CascadeItemVo();
        cascadeItem.setSourceCode(sourceChain.getChainCode());
        cascadeItem.setNewCode(newCode);
        cascadeItem.setSourceName(sourceChain.getChainName());
        cascadeItem.setStatus(status);
        return cascadeItem;
    }

    /**
     * 装组件＝大家一起用同一个（§十二-5）：不复制副本，localResourceId 直接指向源组件——
     * 这样运行时占的内存只跟组件总数有关，跟多少人装过没关系；作者更新、停用、下架都马上生效，
     * 卸载只删安装记录，组件本体不动。
     * 目录归属（§十二-7）：装在哪个目录记在安装记录行上，和源组件行自己的 directory_id 互不影响
     */
    private InstallOutcome shareComponent(DatabusMarketItem item, Long directoryId) {
        DatabusComponent source = componentMapper.selectById(item.getSourceId());
        if (source == null) {
            throw new ServiceException("源组件不存在或已删除");
        }
        return new InstallOutcome(source.getId(), MODE_SHARED, componentHash(source),
            normalizeDirectoryId(directoryId), source.getComponentCode(),
            COMPONENT_STATUS_ENABLED.equals(source.getStatus()), null);
    }

    /**
     * 写安装记录：一行＝一个人对一个物料装了一次（链路只记主链副本，
     * 跟着复制的子链副本不单独记行，§十四-5）
     */
    private DatabusInstall saveInstallRecord(Long itemId, Long userId, InstallOutcome outcome) {
        DatabusInstall record = new DatabusInstall();
        record.setOwnerId(userId);
        record.setMarketItemId(itemId);
        record.setLocalResourceId(outcome.localResourceId());
        record.setMode(outcome.mode());
        // 组件：目录记在安装记录行上。链路这一列留 null——链路的目录记在副本行上，
        // 两边都记就说不清以哪个为准了
        record.setDirectoryId(outcome.directoryId());
        record.setContentHash(outcome.contentHash());
        record.setCreateTime(LocalDateTime.now());
        installMapper.insert(record);
        return record;
    }

    /**
     * 安装结果的返回内容：安装记录 ＋ 物料的展示字段 ＋ 各分支自己的产物
     */
    private DatabusMarketInstallVo toInstallVo(DatabusInstall record, DatabusMarketItem item, Long userId,
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
        // 组件直接看本体现在是启用还是停用；链路复制出来是草稿（没发布），没有开关
        vo.setEnabled(TYPE_COMPONENT.equals(item.getResourceType()) && Boolean.TRUE.equals(outcome.componentEnabled()));
        return vo;
    }

    /**
     * 卸载：按 mode 分开处理本地资源，删安装记录，刷新计数。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(Long resourceId, Long userId) {
        // 连接器没有安装记录（装它就是启用 def 行，不写记录），所以会一路走到最后的
        // 「未找到安装记录」报错，正好；它的启用/停用/卸载都归 /databus/connector/defs 那几个接口管
        DatabusInstall record = installMapper.selectOne(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getOwnerId, userId)
            .eq(DatabusInstall::getLocalResourceId, resourceId)
            .orderByDesc(DatabusInstall::getId)
            .last("limit 1"));
        if (record == null) {
            // 查不到安装记录时（2026-10-24 补的缺口E）：跟着主链复制出来的子链副本
            // 会一起进目录，但安装记录只记主链（§十四-5），副本自己没有记录——
            // 如果传进来的 resourceId 就是我名下的一个这样的副本，那就当卸载处理，直接删掉它。
            // 删链走 chainService 这一个入口（decisions §十五-1/2）：里面做引用检查、清 Rule-DB 的 lf_chain
            DatabusChain orphanCopy = chainMapper.selectById(resourceId);
            if (orphanCopy != null && orphanCopy.getSourceChainId() != null
                && Objects.equals(orphanCopy.getCreateBy(), userId)) {
                chainService.deleteByIds(List.of(resourceId));
                return true;
            }
            throw new ServiceException("未找到对应的安装记录");
        }
        // 判断类型：先查市场物料；源已经被删了就看本地副本落在哪张表里
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
        // 按 mode 分开处理（§十二-5）：只有 FORK（链路副本，或早期装组件时复制的副本）才删本地资源；
        // SHARED 只删安装记录——组件本体是作者和其他装过的人共用的，卸载不能动它
        if (MODE_FORK.equals(record.getMode())) {
            if (TYPE_CHAIN.equals(resourceType)) {
                // 删链之后要做的事都交给 chainService 这一个入口（decisions §十五-1/2）：
                // 引用检查（被别的链引用就不给删，这一批里互相引用的算例外）＋逻辑删
                // ＋清 Rule-DB 的 lf_chain＋清安装记录＋重算计数
                chainService.deleteByIds(List.of(resourceId));
                // 顺带清子链副本（2026-10-24 改的，原来只删最外层那一条）：主链副本删掉之后，
                // 把清单里「没有被还在用的链引用、装完之后一直没动过」的子链副本一起删
                cascadeCleanClosureCopies(item, userId);
                // 安装记录一般已经被 chainService.cleanupInstallRecords 删掉了，下面再删一次只是保险
                // （副本行已经不在时 deleteByIds 不会动记录，这一步保证卸载后不留下指向已删资源的记录）
            } else if (TYPE_COMPONENT.equals(resourceType)) {
                componentMapper.deleteById(resourceId);
            }
        }
        installMapper.deleteById(record.getId());
        if (record.getMarketItemId() != null) {
            refreshCounts(record.getMarketItemId());
        }
        return true;
    }

    /**
     * 卸载主链时顺带清掉跟着复制出来的子链副本（2026-10-24 改的，原来只删最外层那一条）。
     * 先挑出候选：我名下、source_chain_id 在这条源链的依赖清单里、装完之后一直没动过
     * （update_time 还等于 create_time）的副本；
     * 再从里面去掉两类——「被还在用的链引用的」（主链副本已经先删了，这一批候选之间互相引用的算例外）
     * 和「被安装记录指向的」（说明用户单独装过它），剩下的才逻辑删除。
     * 改过的副本（进编辑器存过 / 发布或下线过 / 换过目录 / 标过模板）和被用户自建的链引用的副本都留下——
     * 那些是用户自己的东西，这里只清「装完没碰过」的。
     * 源链已被删、或者查依赖清单报错，都不影响卸载本身：清副本只是顺手多做的一步，出错就不做、也不报错。
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
            // 怎么算「装完没碰过」（decisions §十五-5）：MyBatis-Plus 的 insertFill 会让
            // create_time 和 update_time 相等，之后任何带实体的更新都会刷新 update_time，
            // 所以两者不等就是被改过。以前用 canvas_data IS NULL 判断，那个已经不成立了——
            // 安装时就会把重定向后的画布写进副本，正常上架的链永远不为 NULL，等于一个都清不掉
            .apply("update_time = create_time"));
        if (copies.isEmpty()) {
            return;
        }
        Set<String> batchCodes = copies.stream().map(DatabusChain::getChainCode).collect(Collectors.toSet());
        // 被安装记录指向的副本不清（decisions §十五-4①）：子链副本有可能被用户单独装过
        // （编码一样又都是从同一条源链复制的，就会复用那一份，见 §十四-2，
        // 那条安装记录的 local_resource_id 正好指向这个副本），
        // 删了就会留下「记录还在、资源没了」——那是用户明确装过的东西，得留着
        List<Long> copyIds = copies.stream().map(DatabusChain::getId).filter(Objects::nonNull).toList();
        Set<Long> installedCopyIds = copyIds.isEmpty() ? Set.of()
            : installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
                    .in(DatabusInstall::getLocalResourceId, copyIds)).stream()
                .map(DatabusInstall::getLocalResourceId).collect(Collectors.toSet());
        List<Long> deletable = new ArrayList<>();
        for (DatabusChain copy : copies) {
            if (StringUtils.isBlank(copy.getChainCode())) {
                continue;
            }
            if (installedCopyIds.contains(copy.getId())) {
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
     * 这条链有没有被别的还在用的链引用（和 DatabusChainServiceImpl.validateNotChainReferenced
     * 判断标准一样：先用 LIKE 带引号粗筛一遍 → 再用 collectChainRefs 逐个确认；
     * 逻辑删除的行 MyBatis-Plus 会自动排掉，excludeCodes 是这一批里互相引用的例外）
     */
    private boolean isChainReferencedExternally(String code, Set<String> excludeCodes) {
        List<DatabusChain> candidates = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .like(DatabusChain::getCmpProperty, "\"" + MarketText.escapeLike(code) + "\""));
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
                    // 组件（SHARED）在链里被引用时：前端拿编码反查安装记录行，好显示打开按钮
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
                // 上架者登录名：管理页行上渲染 @作者（宁缺勿滥，物料行没了就不显示）
                vo.setOwnerCode(item.getOwnerCode());
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
     * 移动组件安装所在的目录：只改安装记录行，源组件行自己的 directory_id 不动。
     */
    public Boolean moveInstall(MarketInstallMoveBo bo, Long userId) {
        DatabusInstall record = installMapper.selectById(bo.getInstallId());
        if (record == null || !Objects.equals(record.getOwnerId(), userId)) {
            throw new ServiceException("未找到对应的安装记录");
        }
        if (!MODE_SHARED.equals(record.getMode())) {
            throw new ServiceException("仅组件共享安装支持目录归属移动");
        }
        // 只改安装记录行上「我看到的目录」，源组件行的 directory_id 不动
        // （这里显式 set null 是为了支持把组件拖回「未归组」）
        return installMapper.update(null, Wrappers.<DatabusInstall>lambdaUpdate()
            .eq(DatabusInstall::getId, record.getId())
            .set(DatabusInstall::getDirectoryId, normalizeDirectoryId(bo.getDirectoryId()))) > 0;
    }

    // ---------------------------- 计数刷新 ----------------------------

    /**
     * 重算一个物料的三个缓存计数（install / ref / forkPublish）。
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
     * 安装前的市场检查（decisions §十四-4）：依赖清单里每条链（包括主链）都必须在市场里存在、
     * 而且状态是 ONLINE。
     * <p>
     * 装链路能不能装，就看市场状态——作者改版重新发布时，ONLINE 的物料会被改回 PENDING
     * （decisions §十四-8），这段时间就不给装，所以安装者拿到的永远是过了审的那一份。
     * 不管是查不到还是状态不是 ONLINE（PENDING / REJECTED / OFFLINE），报错都直接说出是哪条链，
     * 安装者不用自己去翻清单里都有什么。
     * <p>
     * 只查链、不查组件：组件是大家共用同一个，运行时直接指向源组件，
     * 源组件被停用了由运行时处理，不在安装这一步拦。
     *
     * @param closure 已经查好的依赖清单（包括主链，链的遍历顺序就是复制顺序）
     */
    public void validateClosureOnMarket(MarketClosure closure) {
        for (DatabusChain chain : closure.chains().values()) {
            DatabusMarketItem item = closure.chainItems().get(chain.getId());
            if (item == null || !STATUS_ONLINE.equals(item.getStatus())) {
                String state = item == null ? "未上架" : item.getStatus();
                throw new ServiceException("依赖链路'" + chain.getChainName() + "'市场状态为" + state + "，暂不可安装");
            }
        }
    }

    /**
     * 目录统一处理：null 或者不是正数，都当「未归组」（null）。
     */
    private Long normalizeDirectoryId(Long directoryId) {
        return (directoryId == null || directoryId <= 0) ? null : directoryId;
    }

    /**
     * 某个人的副本编码前缀：{@code u{ownerId}__}（双下划线是系统保留的分隔符）。
     */
    private String namespacePrefix(Long userId) {
        return "u" + userId + CODE_SEPARATOR;
    }

    /**
     * 生成副本编码：{@code prefix + sourceCode}，超过 maxLen 就截断；
     * 如果和已有编码撞了就在后面加 _2/_3…（加完还是不能超过长度）
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
     * 找一条能直接复用的副本（decisions §十四-2、§十五-6）：在我名下找一条
     * 从同一条市场源链复制来的、装完之后一直没动过的副本，找到就直接用它，不再复制。
     * <p>
     * 不能靠编码来找：编码只认最基础的那个，带 {@code _2}/{@code _3} 后缀的干净副本会被漏掉，
     * 结果就是每装一次就多复制一条。
     * <p>
     * 有两种副本就算是从同一条源链复制来的也不能复用（返回 null，改走 {@link #buildUniqueCode}
     * 从市场原版重新复制一份）：
     * <ul>
     *   <li>被改过的（{@code update_time <> create_time}，判断方式同 §十五-5）——复用它的话，
     *       新装的链会不声不响地跑用户自己改过的逻辑，而不是市场上过了审的那份，全程没有任何提示；</li>
     *   <li>被标成精选模板的（{@code is_template = '1'}）——发布时会被 validateChainReferences 拒掉，
     *       而报错里看不出来是安装这一步造成的，查起来很费劲。</li>
     * </ul>
     * 查询必须限定创建人是我：source_chain_id 指向的是市场源链，别人装的副本也满足这个条件。
     * 老数据的副本 source_chain_id 是 NULL，认不出是不是同一条源链，就返回 null 另外复制一条
     * （这样做只会多一条副本，不会用错，所以不做数据迁移）。
     */
    private DatabusChain findReusableSameSourceCopy(Long userId, DatabusChain sourceChain) {
        List<DatabusChain> candidates = chainMapper.selectList(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getCreateBy, userId)
            .eq(DatabusChain::getSourceChainId, sourceChain.getId())
            .apply("update_time = create_time")
            .and(w -> w.isNull(DatabusChain::getIsTemplate)
                .or().ne(DatabusChain::getIsTemplate, TEMPLATE_FLAG_YES))
            .orderByAsc(DatabusChain::getId));
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 源已被删时，靠本地副本落在哪张表来判断资源类型。
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
     * 按链路内容（画布 + EL）算一个标识值，内容一样算出来就一样。
     */
    private String chainHash(DatabusChain chain) {
        String raw = nullToEmpty(chain.getCanvasData()) + HASH_SEP + nullToEmpty(chain.getElExpression());
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 按组件内容（脚本正文 + 参数 schema + 节点类型 + 配置形态）算一个标识值。
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
}
