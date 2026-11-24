package org.dromara.databus.market;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusConnectorDef;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketInstallMoveBo;
import org.dromara.databus.domain.vo.DatabusMarketInstallVo;
import org.dromara.databus.enums.ConnectorDefStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusConnectorDefMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.MarketClosure;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import static org.dromara.databus.constant.MarketConstants.MODE_FORK;
import static org.dromara.databus.constant.MarketConstants.MODE_SHARED;
import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;
import static org.dromara.databus.constant.MarketConstants.TYPE_CONNECTOR;

/**
 * 物料市场——安装与卸载（设计文档 databus-material-market.md §五~§六、§十二，decisions §十四~§十五）。
 * <p>
 * 三类物料装法不一样：
 * <ul>
 *   <li>链路＝复制一份自己的副本：主链和它引用的全部子链（包括子链再引用的）一条条复制出来，
 *       每条都是独立的行，都放在「我的」目录最外层；安装记录只记主链这一条，
 *       跟着复制出来的子链副本靠 source_chain_id 能查到是从哪条链复制来的；</li>
 *   <li>组件＝大家一起用同一个：不复制副本，localResourceId 直接指向源组件，作者改了两边马上都生效；</li>
 *   <li>连接器＝写一条安装记录：连接器本体是全局共享的一份，安装不复制也不启用/停用，
 *       只决定「我」在自己的清单和建链选择器里能不能看到它；启用是治理动作（§15.3）。</li>
 * </ul>
 * 卸载按 mode 分开处理：FORK 删本地副本，顺带把「装完之后一直没动过」的子链副本一起删；
 * SHARED 只删安装记录，组件本体不动。
 * <p>
 * 编码规则：链路副本的编码统一加 {@code u{ownerId}__} 前缀（双下划线是系统保留的分隔符，
 * 源编码里不会有 {@code __}，所以不同人的副本编码不会撞）。
 * install/ref/forkPublish 这三个计数列是缓存值，安装和卸载之后同步刷新（§九：不放在实时查询里算）——
 * 刷新逻辑在 {@link MarketInstallQuerySupport}。
 * <p>
 * 本类只保留入口与事务边界；链路复制、卸载清理、列表查询分别委托给同包的
 * {@link MarketChainCopySupport}、{@link MarketUninstallSupport}、{@link MarketInstallQuerySupport}，
 * 编码与哈希等纯工具在 {@link MarketInstallCodeSupport}。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketInstallService {

    private final DatabusMarketItemMapper marketItemMapper;

    private final DatabusInstallMapper installMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    private final DatabusConnectorDefMapper connectorDefMapper;

    /**
     * 链路服务：查依赖清单，以及删链的唯一入口（里面会做引用检查、清 Rule-DB 的 lf_chain、清安装记录）
     */
    private final IDatabusChainService chainService;

    private final MarketInstallCodeSupport codeSupport;

    private final MarketChainCopySupport chainCopySupport;

    private final MarketUninstallSupport uninstallSupport;

    private final MarketInstallQuerySupport querySupport;

    /**
     * 安装物料：链路复制一份副本 / 组件和连接器写一条安装记录（本体共享）。
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

        // 装连接器＝写一条个人安装记录：连接器本体全局只有一份，安装不改它的启停状态
        if (TYPE_CONNECTOR.equals(item.getResourceType())) {
            return installConnector(item, userId);
        }
        assertNotInstalled(itemId, userId);

        InstallOutcome outcome;
        if (TYPE_CHAIN.equals(item.getResourceType())) {
            outcome = chainCopySupport.forkChainClosure(item, directoryId, userId);
        } else if (TYPE_COMPONENT.equals(item.getResourceType())) {
            outcome = chainCopySupport.shareComponent(item, directoryId);
        } else {
            throw new ServiceException("不支持的物料类型：" + item.getResourceType());
        }

        DatabusInstall record = saveInstallRecord(itemId, userId, outcome);
        querySupport.refreshCounts(itemId);
        return querySupport.toInstallVo(record, item, userId, outcome);
    }

    /**
     * 装连接器（§15.3）：连接器必须已由治理员启用；安装只给当前用户写一条安装记录，
     * 不改 def 的启停状态（启用/停用是治理动作），也不复制副本。
     * 「已装」只影响这个人在自己清单和建链选择器里看不看得到它。
     */
    private DatabusMarketInstallVo installConnector(DatabusMarketItem item, Long userId) {
        DatabusConnectorDef def = connectorDefMapper.selectById(item.getSourceId());
        if (def == null) {
            throw new ServiceException("源连接器不存在或已卸载");
        }
        if (!ConnectorDefStatusEnum.ENABLED.getCode().equals(def.getStatus())) {
            throw new ServiceException("连接器尚未启用，暂不可安装");
        }
        assertNotInstalled(item.getId(), userId);
        DatabusInstall record = new DatabusInstall();
        record.setOwnerId(userId);
        record.setMarketItemId(item.getId());
        record.setLocalResourceId(def.getId());
        record.setLocalResourceType(TYPE_CONNECTOR);
        record.setMode(MODE_SHARED);
        record.setCreateTime(LocalDateTime.now());
        installMapper.insert(record);
        // install_count 是安装记录条数，连接器和组件一样按记录累计
        querySupport.refreshCounts(item.getId());
        DatabusMarketInstallVo vo = new DatabusMarketInstallVo();
        vo.setId(record.getId());
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
        vo.setCodeDisplay(item.getCodeDisplay());
        vo.setCreateTime(record.getCreateTime());
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
     * 写安装记录：一行＝一个人对一个物料装了一次（链路只记主链副本，
     * 跟着复制的子链副本不单独记行，§十四-5）
     */
    private DatabusInstall saveInstallRecord(Long itemId, Long userId, InstallOutcome outcome) {
        DatabusInstall record = new DatabusInstall();
        record.setOwnerId(userId);
        record.setMarketItemId(itemId);
        record.setLocalResourceId(outcome.localResourceId());
        record.setLocalResourceType(outcome.localResourceType());
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
     * 卸载：按 mode 分开处理本地资源，删安装记录，刷新计数。
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean uninstall(Long resourceId, Long userId) {
        // 连接器也写个人安装记录，和组件一样是 SHARED：卸载只删记录，不停用连接器
        // （停用/卸载连接器本体走 /databus/connector/defs 那几个治理接口）
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
        // 类型直接读安装记录自己的判别列，不再反查物料、也不靠「id 落在哪张表」来猜：
        // 物料被作者删掉、本地资源被删掉都不影响卸载走哪条分支。
        // 物料行仍要查一次——级联清子链副本需要它拿源链 id
        DatabusMarketItem item = record.getMarketItemId() == null ? null
            : marketItemMapper.selectById(record.getMarketItemId());
        String resourceType = record.getLocalResourceType();
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
                uninstallSupport.cascadeCleanClosureCopies(item, userId);
                // 安装记录一般已经被 chainService.cleanupInstallRecords 删掉了，下面再删一次只是保险
                // （副本行已经不在时 deleteByIds 不会动记录，这一步保证卸载后不留下指向已删资源的记录）
            } else if (TYPE_COMPONENT.equals(resourceType)) {
                // 早期 FORK 装组件留下的本地副本：真删，否则逻辑删残留会一直占着 uk_component_code
                componentMapper.deletePhysicallyByIds(List.of(resourceId));
            }
        }
        installMapper.deleteById(record.getId());
        if (record.getMarketItemId() != null) {
            querySupport.refreshCounts(record.getMarketItemId());
        }
        return true;
    }

    /**
     * 我装过的东西：把安装记录和物料行、本地资源关联起来，补上名称、图标、启用状态这些展示字段。
     */
    public List<DatabusMarketInstallVo> queryInstalled(Long userId) {
        return querySupport.queryInstalled(userId);
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
            .set(DatabusInstall::getDirectoryId, codeSupport.normalizeDirectoryId(bo.getDirectoryId()))) > 0;
    }

    /**
     * 安装前的市场检查（decisions §十四-4）：依赖清单里每条链（包括主链）都必须在市场里存在、
     * 而且状态是 ONLINE。实现在 {@link MarketChainCopySupport#validateClosureOnMarket}。
     *
     * @param closure 已经查好的依赖清单（包括主链，链的遍历顺序就是复制顺序）
     */
    public void validateClosureOnMarket(MarketClosure closure) {
        chainCopySupport.validateClosureOnMarket(closure);
    }
}
