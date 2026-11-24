package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.ChainPublishResultVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.ChainForkRedirector;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;
import static org.dromara.databus.constant.MarketConstants.STATUS_ONLINE;
import static org.dromara.databus.constant.MarketConstants.STATUS_PENDING;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;
import static org.dromara.databus.constant.MarketConstants.TYPE_COMPONENT;

/**
 * 链路发布/下线：状态流转与 Rule-DB 联动，外加发布侧的闭包引导清单与改版冻结钩子。
 * <p>
 * 发布先推规则后改状态（推失败状态不变，不留"已发布但引擎无规则"的不可执行窗口）；
 * 下线先改状态后移除规则（移除失败残留无害，重发覆盖）。
 * <p>
 * 事务边界留在 {@link DatabusChainServiceImpl#publish}，本类只承接实现。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ChainPublishSupport {

    private final DatabusChainMapper chainMapper;

    private final ExpressGenerator expressGenerator;

    private final RulePublishService rulePublishService;

    private final DatabusMarketItemMapper marketItemMapper;

    /**
     * 组件查询（decisions §十四-6 发布引导）：闭包业务组件叶子 → databus_component 行，
     * 判归属（自己的进引导清单）与市场行定位（sourceId＝组件行 id）
     */
    private final DatabusComponentMapper componentMapper;

    private final ChainGraphSupport graphSupport;

    /**
     * 发布链路：DRAFT 草稿/DISABLED 已下线 → ENABLED 已发布 + version+1。
     *
     * @param id     链路主键
     * @param userId 发布操作者（判闭包依赖「自己的/他人的」）
     * @return 引导清单＋他人未上架组件提示；发布失败（理论不可达）返回 null
     */
    public ChainPublishResultVo publish(Long id, Long userId) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        // 模板红线：模板是样板不是生产资产，不推 Rule-DB、不进执行入口；请复制副本后发布
        if (TEMPLATE_FLAG_YES.equals(chain.getIsTemplate())) {
            throw new ServiceException("精选模板不可发布，请使用模板复制出自己的链路后发布副本");
        }
        // 发布即固化当前编排为运行版本。组件树由实体直接持有（TypeHandler 反序列化），
        // 是 EL 的唯一权威源，不依赖可能为空的存量 el_expression 字段
        CmpProperty cmpProperty = chain.getCmpProperty();
        if (cmpProperty == null) {
            throw new ServiceException("链路未编排组件，不能发布");
        }
        String elExpression = expressGenerator.generateEL(cmpProperty).getElStr();
        if (StringUtils.isBlank(elExpression)) {
            throw new ServiceException("链路未编排组件，不能发布");
        }
        String status = chain.getStatus();
        if (!ChainStatusEnum.DRAFT.getCode().equals(status)
            && !ChainStatusEnum.DISABLED.getCode().equals(status)) {
            throw new ServiceException("当前状态不允许发布（仅草稿/已下线可发布）");
        }
        // 子流程引用把关（decisions.md 第七章）：草稿期不拦（编排自由），发布这道关一次性校验——
        // ① 循环引用（A→B→A）成环则引擎会无限递归，只能靠运行时深度保险丝兜底，代价是一次失败执行；
        // ② 被引子链必须已发布，否则主链能发布成功、执行时引擎在 chainMap 里查不到子链直接报错，
        //    故障点离操作点太远，排查成本高
        graphSupport.validateReferences(chain);
        // 发布侧引导收集（decisions §十四-6）：闭包内他人未上架链在此拒绝（gate 必须先于
        // 任何变更——Rule-DB 推送/状态更新均未发生）；自己的未上架物料（子链＋组件）进引导清单，
        // 发布成功后随响应返回供前端弹窗「上架会将子链和组件一并上架」→ 确认 → 批量提审
        ChainPublishResultVo result = collectUnlistedClosure(chain, userId);
        // 先推 Rule-DB（脚本冲突/引擎异常时状态不变，重试即重推；推成功而后续状态更新失败
        // 会留孤儿规则，重发时被 UPSERT 覆盖，无害）；本方法事务仅覆盖状态更新＋改版冻结钩子
        // （冻结失败回滚状态可重试，Rule-DB 孤儿规则同口径无害）
        rulePublishService.publishChain(chain.getChainCode(), elExpression, cmpProperty);
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setStatus(ChainStatusEnum.ENABLED.getCode());
        update.setVersion(chain.getVersion() + 1);
        // 回写实时生成的 EL，保持表内字段与组件树一致（add/update 也是这口径）
        update.setElExpression(elExpression);
        boolean flag = chainMapper.updateById(update) > 0;
        if (flag) {
            // 改版冻结（decisions §十四-8）：审核状态挂版本不挂链——改版发布即产生未审内容，
            // 指向本链的 ONLINE 市场项退回 PENDING（install 闭包校验拒装＝冻结），重审通过＝解冻
            freezeOnlineMarketItem(id);
        }
        // updateById 影响行数为 0（理论不可达，行刚读过）：返回 null 由控制器按失败口径回包
        return flag ? result : null;
    }

    /**
     * 下线链路：ENABLED 已发布 → DISABLED 已下线，定义保留可重新发布。
     *
     * @param id 链路主键
     * @return 是否下线成功
     */
    public Boolean offline(Long id) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (!ChainStatusEnum.ENABLED.getCode().equals(chain.getStatus())) {
            throw new ServiceException("当前状态不允许下线（仅已发布可下线）");
        }
        // 先改状态（下线立即可见），再移除 Rule-DB 规则；移除失败仅记日志不阻断——
        // 残留规则因状态已下线不会被执行（执行入口按状态校验），重新发布时被覆盖
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setStatus(ChainStatusEnum.DISABLED.getCode());
        boolean flag = chainMapper.updateById(update) > 0;
        if (flag) {
            rulePublishService.removeChainQuietly(chain.getChainCode());
        }
        return flag;
    }

    /**
     * 改版冻结钩子（decisions §十四-8 版本级审核状态＋改版冻结安装）：
     * 链改版发布后，把指向本链的 ONLINE 市场项置回 PENDING 并清审核痕迹
     * （lambdaUpdate().set(xxx, null) 清痕模式照抄 MarketPublishService#publish）。
     * <p>
     * 效果链：改版 → PENDING → install 闭包校验拒装（validateClosureOnMarket 只认 ONLINE＝冻结）
     * → 重审通过 → ONLINE（解冻）。未上架/非 ONLINE（已在审/已驳回/已下架）不动——
     * 冻结只针对「已过审在架、内容已换」的失配态。返回 0 行（本链无在架市场项）无副作用。
     *
     * @param chainId 改版发布的链路 id
     */
    private void freezeOnlineMarketItem(Long chainId) {
        marketItemMapper.update(null, Wrappers.<DatabusMarketItem>lambdaUpdate()
            .eq(DatabusMarketItem::getResourceType, TYPE_CHAIN)
            .eq(DatabusMarketItem::getSourceId, chainId)
            .eq(DatabusMarketItem::getStatus, STATUS_ONLINE)
            .set(DatabusMarketItem::getStatus, STATUS_PENDING)
            .set(DatabusMarketItem::getRemark, "")
            .set(DatabusMarketItem::getReviewBy, null)
            .set(DatabusMarketItem::getReviewTime, null)
            .set(DatabusMarketItem::getUpdateTime, LocalDateTime.now()));
    }

    /**
     * 发布侧闭包未上架物料收集（decisions §十四-6 发布引导上架）。
     * <p>
     * 「未上架」＝市场查不到行（任意状态都算已上架）——而非「非 ONLINE」：改版冻结
     * （§十四-8）会把 ONLINE 置回 PENDING，若按非 ONLINE 判未上架，引用冻结链的主链
     * 会被误拒，冻结语义（内容已换待重审）不该传导成「从未上架」。
     * <ul>
     *   <li>链维度：闭包子链有市场行跳过；自己的未上架子链进引导清单；他人的（含 create_by
     *       为空的系统链，归属校验同 MarketPublishService#publish 口径——系统资源
     *       非作者无权代提）直接拒绝发布。主链自身无市场行且归属发布者时进清单第一项
     *       （2026-10-24 闭包联审：「提交审核主链」心智，避免依赖在审而主链无单的半截状态）。</li>
     *   <li>组件维度：闭包全部链（含主链）的业务组件叶子（ChainForkRedirector 共用口径）
     *       → 组件行 → 市场行；悬空引用跳过（引导是建议性收集，把关留给终审 gate）；
     *       自己的进引导清单；他人的不阻塞仅列名提示（组件运行不依赖市场身份）。</li>
     * </ul>
     * 本方法纯读不落库，调用点在 validateReferences 之后、Rule-DB 推送之前
     * （拒绝必须发生在任何变更前）。
     *
     * @param chain  待发布主链
     * @param userId 发布操作者（判「自己的/他人的」）
     * @return 引导清单＋他人未上架组件提示（两者都可为空）
     */
    private ChainPublishResultVo collectUnlistedClosure(DatabusChain chain, Long userId) {
        LinkedHashMap<String, DatabusChain> closure = graphSupport.collectClosure(chain.getChainCode());
        ChainPublishResultVo result = new ChainPublishResultVo();
        List<ChainPublishResultVo.GuideItemVo> guideItems = new ArrayList<>();
        List<String> otherUnlistedComponents = new ArrayList<>();

        // 链维度：市场行 in 查一次（同源 CHAIN item 至多一行，toMap 合并防御性取首行）
        List<Long> closureChainIds = closure.values().stream().map(DatabusChain::getId).toList();
        Map<Long, DatabusMarketItem> chainItemMap = closureChainIds.isEmpty() ? Map.of()
            : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, TYPE_CHAIN)
                    .in(DatabusMarketItem::getSourceId, closureChainIds))
                .stream()
                .collect(Collectors.toMap(DatabusMarketItem::getSourceId, item -> item, (a, b) -> a));
        for (DatabusChain member : closure.values()) {
            // 主链在循环外单独处理（要进清单第一项，见下方prepend）
            if (member.getId().equals(chain.getId())) {
                continue;
            }
            if (chainItemMap.containsKey(member.getId())) {
                continue;
            }
            if (Objects.equals(member.getCreateBy(), userId)) {
                ChainPublishResultVo.GuideItemVo guide = new ChainPublishResultVo.GuideItemVo();
                guide.setResourceType(TYPE_CHAIN);
                guide.setSourceId(member.getId());
                guide.setName(member.getChainName());
                guideItems.add(guide);
            } else {
                throw new ServiceException("依赖链路'" + member.getChainName() + "'未上架市场，无权代他人提审，请先联系其作者上架");
            }
        }

        // 组件维度：闭包全部链的业务组件叶子（含主链），去重保序
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
                    .collect(Collectors.toMap(DatabusMarketItem::getSourceId, item -> item, (a, b) -> a));
            for (String code : componentCodes) {
                // 悬空组件引用：引导只建议不拦（终审 gate 拒「组件已不存在」，两级口径）
                DatabusComponent component = componentMap.get(code);
                if (component == null || componentItemMap.containsKey(component.getId())) {
                    continue;
                }
                if (Objects.equals(component.getCreateBy(), userId)) {
                    ChainPublishResultVo.GuideItemVo guide = new ChainPublishResultVo.GuideItemVo();
                    guide.setResourceType(TYPE_COMPONENT);
                    guide.setSourceId(component.getId());
                    guide.setName(component.getComponentName());
                    guideItems.add(guide);
                } else {
                    otherUnlistedComponents.add(component.getComponentName());
                }
            }
        }
        // 主链进清单第一项（2026-10-24 闭包联审）：主链自身无市场行且归属发布者时一并引导——
        // 用户心智是「提交审核主链」，只提依赖会造出「依赖在审、主链没单」的半截状态，
        // 联审入口（主链审核单）无从谈起。非作者发布他人链时静默跳过（上架仍属作者动作）。
        if (!chainItemMap.containsKey(chain.getId()) && Objects.equals(chain.getCreateBy(), userId)) {
            ChainPublishResultVo.GuideItemVo self = new ChainPublishResultVo.GuideItemVo();
            self.setResourceType(TYPE_CHAIN);
            self.setSourceId(chain.getId());
            self.setName(chain.getChainName());
            guideItems.add(0, self);
        }
        result.setGuideItems(guideItems);
        result.setOtherUnlistedComponents(otherUnlistedComponents);
        return result;
    }

}
