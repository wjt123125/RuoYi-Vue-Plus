package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;

/**
 * 卸载链路后的收尾：级联清理跟着复制出来、装完没动过的子链副本，并判断某条链是否仍被外部引用。
 * <p>
 * 只被 MarketInstallService 的 uninstall 调用，不做安装记录判定和删除本身，
 * 也不反向依赖 MarketInstallService。
 */
@RequiredArgsConstructor
@Component
public class MarketUninstallSupport {

    private final DatabusChainMapper chainMapper;

    private final DatabusInstallMapper installMapper;

    private final IDatabusChainService chainService;

    /**
     * 卸载主链时顺带清掉跟着复制出来的子链副本。
     * 先挑出候选：我名下、source_chain_id 在这条源链的依赖清单里、装完之后一直没动过
     * （update_time 还等于 create_time）的副本；
     * 再从里面去掉两类——「被还在用的链引用的」（主链副本已经先删了，这一批候选之间互相引用的算例外）
     * 和「被安装记录指向的」（说明用户单独装过它），剩下的才逻辑删除。
     * 改过的副本（进编辑器存过 / 发布或下线过 / 换过目录 / 标过模板）和被用户自建的链引用的副本都留下——
     * 那些是用户自己的东西，这里只清「装完没碰过」的。
     * 源链已被删、或者查依赖清单报错，都不影响卸载本身：清副本只是顺手多做的一步，出错就不做、也不报错。
     */
    public void cascadeCleanClosureCopies(DatabusMarketItem item, Long userId) {
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
                    .eq(DatabusInstall::getLocalResourceType, TYPE_CHAIN)
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
}
