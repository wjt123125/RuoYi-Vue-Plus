package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.dromara.databus.constant.ChainConstants.CHAIN_REF_TYPE;
import static org.dromara.databus.constant.ChainConstants.REF_PATH_SEPARATOR;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;

/**
 * 链路引用图：从 cmp_property 组件树里取 CHAIN 引用、展开安装闭包、发布前校验、删除前反向引用检查。
 * <p>
 * 四处能力共用同一套「什么算 CHAIN 节点」的判定（{@link #collectRefs(CmpProperty)}）和同一套
 * DFS 三色标记，拒绝文案按 decisions §十四-4 保持发布/安装双口径一致。
 * <p>
 * 只被 {@link DatabusChainServiceImpl}、{@link ChainWriteSupport}、{@link ChainPublishSupport}
 * 调用，纯读不落库。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ChainGraphSupport {

    private final DatabusChainMapper chainMapper;

    /**
     * 收集组件树（含 condition 分支与全部 children）中的 CHAIN 引用编码，按出现顺序去重。
     *
     * @param root 组件树节点
     * @return 子链 chainCode 列表，无引用时为空列表
     */
    public List<String> collectRefs(CmpProperty root) {
        List<String> refs = new ArrayList<>();
        collectRefs(root, refs);
        return refs;
    }

    private void collectRefs(CmpProperty node, List<String> refs) {
        if (node == null) {
            return;
        }
        if (CHAIN_REF_TYPE.equalsIgnoreCase(node.getType())) {
            // CHAIN 节点的 chainCode 存在顶层 id（buildChildrenChain 的 .id(chainCode)），
            // 历史数据兜底读 properties.id
            String code = StringUtils.isNotBlank(node.getId()) ? node.getId()
                : (node.getProperties() != null ? node.getProperties().getId() : null);
            if (StringUtils.isNotBlank(code) && !refs.contains(code)) {
                refs.add(code);
            }
        }
        collectRefs(node.getCondition(), refs);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectRefs(child, refs);
            }
        }
    }

    /**
     * 收集链路安装闭包（decisions §十四-1 链路安装全 fork）：以 rootChainCode 为根沿组件树
     * CHAIN 引用递归下钻，返回「主链＋全部传递子链」（纯读不落库）。
     * <p>
     * entry 顺序为 post-order（被引用者先于引用者），即安装复制的施工顺序——子链先就位，
     * 主链引用重定向时新编码已存在。环引用与四类非法子链（不存在/模板/未发布/未编排）的
     * 拒绝口径与发布侧校验一致。
     *
     * @param rootChainCode 主链编码
     * @return 闭包（key=chainCode，含主链自身，entry 顺序即复制顺序）
     */
    public LinkedHashMap<String, DatabusChain> collectClosure(String rootChainCode) {
        DatabusChain root = chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, rootChainCode));
        if (root == null) {
            throw new ServiceException("链路'" + rootChainCode + "'不存在或已删除");
        }
        List<String> path = new ArrayList<>();
        path.add(rootChainCode);
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        scanClosure(root, path, new HashSet<>(), closure);
        // 主链最后入列（其全部子链已就位），与发布侧「子链先合法、主链后校验」同口径
        closure.put(root.getChainCode(), root);
        return closure;
    }

    /**
     * 闭包递归收集：post-order——子树收齐后父链入列，被引用者先于引用者。
     * <p>
     * 三色标记与 {@link #walkReferences} 同法：{@code path} 灰色集防环并回显完整路径，
     * {@code collected} 黑色集菱形剪枝（B、C 同引 D，D 只展开一次）。
     *
     * @param chain     当前展开链路
     * @param path      当前递归路径（chainCode 序列，灰）
     * @param collected 闭包已收编 chainCode（黑，含子树完整展开的剪枝依据）
     * @param closure   闭包累积（key=chainCode，entry 顺序即复制顺序）
     */
    private void scanClosure(DatabusChain chain, List<String> path, Set<String> collected,
                             LinkedHashMap<String, DatabusChain> closure) {
        for (String refCode : collectRefs(chain.getCmpProperty())) {
            int hit = path.indexOf(refCode);
            if (hit >= 0) {
                // 回显完整环路径（如 A → B → A），用户照着改一处引用即可（与发布侧同法）
                List<String> cycle = new ArrayList<>(path.subList(hit, path.size()));
                cycle.add(refCode);
                throw new ServiceException("子流程循环引用，不能安装：" + String.join(REF_PATH_SEPARATOR, cycle));
            }
            if (collected.contains(refCode)) {
                continue;
            }
            DatabusChain sub = chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getChainCode, refCode));
            if (sub == null) {
                throw new ServiceException("引用的子流程'" + refCode + "'已不存在，请检查引用或移除该节点");
            }
            // 模板红线与主链同口径：模板不进执行入口，安装闭包里同样拒绝出现
            if (TEMPLATE_FLAG_YES.equals(sub.getIsTemplate())) {
                throw new ServiceException("子流程'" + refCode + "'是精选模板，运行时不可被引用，请复制副本后引用副本");
            }
            if (!ChainStatusEnum.ENABLED.getCode().equals(sub.getStatus())) {
                throw new ServiceException("子流程'" + refCode + "'尚未发布，请先发布它再发布本链路");
            }
            if (sub.getCmpProperty() == null) {
                throw new ServiceException("子流程'" + refCode + "'未编排组件，请先补全它的编排");
            }
            path.add(refCode);
            scanClosure(sub, path, collected, closure);
            path.remove(path.size() - 1);
            closure.put(refCode, sub);
            collected.add(refCode);
        }
    }

    /**
     * 发布前校验子流程（CHAIN）引用：沿组件树递归下钻，检出循环引用与不可执行的子链。
     * <p>
     * 采用 DFS 三色标记：{@code path} 为灰色（当前递归路径上，命中即成环）、{@code checked} 为黑色
     * （子图已完整校验通过，直接剪枝——菱形引用 A→B、A→C、B→D、C→D 时 D 只展开一次）。
     *
     * @param chain 待发布链路
     */
    public void validateReferences(DatabusChain chain) {
        List<String> path = new ArrayList<>();
        path.add(chain.getChainCode());
        walkReferences(chain.getCmpProperty(), path, new HashSet<>());
    }

    /**
     * 递归校验组件树里的全部 CHAIN 引用。
     *
     * @param root    组件树节点
     * @param path    灰色集：当前递归路径（自待发布链路起的 chainCode 序列）
     * @param checked 黑色集：已完整校验通过的子链 chainCode
     */
    private void walkReferences(CmpProperty root, List<String> path, Set<String> checked) {
        for (String refCode : collectRefs(root)) {
            int hit = path.indexOf(refCode);
            if (hit >= 0) {
                // 回显完整环路径（如 A → B → A），用户照着改一处引用即可，不必自己去猜哪层绕回来了
                List<String> cycle = new ArrayList<>(path.subList(hit, path.size()));
                cycle.add(refCode);
                throw new ServiceException("子流程循环引用，不能发布：" + String.join(REF_PATH_SEPARATOR, cycle));
            }
            if (!checked.add(refCode)) {
                continue;
            }
            DatabusChain sub = chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getChainCode, refCode));
            if (sub == null) {
                throw new ServiceException("引用的子流程'" + refCode + "'已不存在，请检查引用或移除该节点");
            }
            // 模板红线与主链同口径：模板不进执行入口，运行时引擎查不到它的规则
            if (TEMPLATE_FLAG_YES.equals(sub.getIsTemplate())) {
                throw new ServiceException("子流程'" + refCode + "'是精选模板，运行时不可被引用，请复制副本后引用副本");
            }
            if (!ChainStatusEnum.ENABLED.getCode().equals(sub.getStatus())) {
                throw new ServiceException("子流程'" + refCode + "'尚未发布，请先发布它再发布本链路");
            }
            if (sub.getCmpProperty() == null) {
                throw new ServiceException("子流程'" + refCode + "'未编排组件，请先补全它的编排");
            }
            path.add(refCode);
            walkReferences(sub.getCmpProperty(), path, checked);
            path.remove(path.size() - 1);
        }
    }

    /**
     * 删除前校验待删链路是否仍被其他链路引用（decisions §十四-10 删除治理统一：
     * 对齐连接删除 validateNotReferenced 先例，全系统一套规则）。
     * <p>
     * 两段式：cmp_property LIKE 粗筛缩小候选集（带引号防同前缀误伤），再内存里用
     * {@link #collectRefs(CmpProperty)} 精确核对（LIKE 无法区分 JSON 里的普通组件 id 与 CHAIN 引用）。
     * 同批互引不算外部引用——本批一起删的链路互相引用时，引用关系随删除消亡，允许删。
     *
     * @param chains 本批待删链路（selectByIds 的结果）
     */
    public void assertNotReferenced(List<DatabusChain> chains) {
        if (chains == null || chains.isEmpty()) {
            return;
        }
        List<String> codes = new ArrayList<>();
        for (DatabusChain chain : chains) {
            if (StringUtils.isNotBlank(chain.getChainCode())) {
                codes.add(chain.getChainCode());
            }
        }
        if (codes.isEmpty()) {
            return;
        }
        // 同批互引豁免集：本批待删链之间的引用不算外部引用
        Set<String> deletingCodes = new HashSet<>(codes);
        // LIKE 粗筛（带引号 pattern，MP 外层自动包 %）：
        // 不做 select 列裁剪——粗筛结果集小，避免 autoResultMap typeHandler 列裁剪风险
        LambdaQueryWrapper<DatabusChain> wrapper = Wrappers.<DatabusChain>lambdaQuery()
            .and(orGroup -> {
                for (int i = 0; i < codes.size(); i++) {
                    if (i > 0) {
                        orGroup.or();
                    }
                    orGroup.like(DatabusChain::getCmpProperty, "\"" + escapeLike(codes.get(i)) + "\"");
                }
            });
        List<DatabusChain> candidates = chainMapper.selectList(wrapper);
        if (candidates.isEmpty()) {
            return;
        }
        List<String> referrers = new ArrayList<>();
        for (DatabusChain candidate : candidates) {
            if (deletingCodes.contains(candidate.getChainCode())) {
                continue;
            }
            List<String> refs = collectRefs(candidate.getCmpProperty());
            for (String code : codes) {
                if (refs.contains(code)) {
                    // 一条引用链只列一次，用户照名字找到链路移除引用即可
                    referrers.add(candidate.getChainName() + "(" + candidate.getChainCode() + ")");
                    break;
                }
            }
        }
        if (!referrers.isEmpty()) {
            throw new ServiceException("所选链路仍被其他链路引用，无法删除：" + String.join("、", referrers) + "；请先在对应链路中移除引用");
        }
    }

    /**
     * LIKE 通配符转义（\ % _），与 SysDatabusConnectionServiceImpl 同口径。
     */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

}
