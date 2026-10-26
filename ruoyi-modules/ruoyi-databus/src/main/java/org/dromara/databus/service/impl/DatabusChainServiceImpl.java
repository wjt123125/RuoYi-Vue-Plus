package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.ChainCopyBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.TemplateMarkBo;
import org.dromara.databus.domain.vo.ChainPublishResultVo;
import org.dromara.databus.domain.vo.ChainStatsVo;
import org.dromara.databus.domain.vo.CopySuggestionVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.context.InputParamValidator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.LogLevelEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.ChainForkRedirector;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 链路定义 Service 实现。
 * <p>
 * 阶段 1A 维护草稿（version=1/status=0）；阶段 3 链路管理页补 publish/offline 状态流转：
 * 发布 0草稿/2已下线 → 1已发布 + version+1；下线 1已发布 → 2已下线（定义保留可重新发布）。
 * <p>
 * 发布/下线/删除与 Rule-DB 联动（{@link RulePublishService} 推送/移除 lf_chain 规则）：
 * 发布先推规则后改状态（推失败状态不变，不留"已发布但引擎无规则"的不可执行窗口）；
 * 下线先改状态后移除规则（移除失败残留无害，重发覆盖）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusChainServiceImpl implements IDatabusChainService {

    /**
     * 草稿初始版本
     */
    private static final int DRAFT_VERSION = 1;

    /**
     * 精选模板标记（is_template：0否 1是）
     */
    private static final String TEMPLATE_FLAG_NO = "0";
    private static final String TEMPLATE_FLAG_YES = "1";

    /**
     * 模板默认排序（未显式指定时）
     */
    private static final int TEMPLATE_DEFAULT_SORT = 0;

    /**
     * 链路名称/编码最大长度（与 Bo @Size 约束一致）
     */
    private static final int CHAIN_NAME_MAX_LEN = 100;
    private static final int CHAIN_CODE_MAX_LEN = 100;

    /**
     * 副本名称后缀
     */
    private static final String COPY_NAME_SUFFIX = "副本";

    /**
     * 副本编码建议的起始序号（源编码_2、_3 ……）
     */
    private static final int COPY_CODE_SEQ_START = 2;

    /**
     * 副本编码建议序号上限（防御性，正常数据远到不了）
     */
    private static final int COPY_CODE_SEQ_MAX = 9999;

    /**
     * 编码结尾「_数字」：建议值先剥离源编码既有 _N 尾缀，避免 xxx_2_2
     */
    private static final Pattern CODE_TRAILING_SEQ = Pattern.compile("_\\d+$");

    /**
     * 子流程引用节点的组件类型（与 ExpressParserEnum.CHAIN、前端画布同值）
     */
    private static final String CHAIN_REF_TYPE = "CHAIN";

    /**
     * 循环引用报错里的路径分隔符
     */
    private static final String REF_PATH_SEPARATOR = " → ";

    /**
     * 市场物料类型/状态（与 MarketResourceTypes / MarketStates 同值常量，改版冻结钩子使用）
     */
    private static final String MARKET_TYPE_CHAIN = "CHAIN";
    private static final String MARKET_TYPE_COMPONENT = "COMPONENT";
    private static final String MARKET_STATUS_ONLINE = "ONLINE";
    private static final String MARKET_STATUS_PENDING = "PENDING";

    private final DatabusChainMapper chainMapper;

    private final ExpressGenerator expressGenerator;

    private final RulePublishService rulePublishService;

    private final DatabusMarketItemMapper marketItemMapper;

    /**
     * 组件查询（decisions §十四-6 发布引导）：闭包业务组件叶子 → databus_component 行，
     * 判归属（自己的进引导清单）与市场行定位（sourceId＝组件行 id）
     */
    private final DatabusComponentMapper componentMapper;

    /**
     * 安装血缘查询（2026-10-24）：FORK 副本被直接删除时级联清安装记录，
     * 防「已安装」列表留悬空卡片（打开是空画布）
     */
    private final DatabusInstallMapper installMapper;

    @Override
    public DatabusChainVo queryById(Long id) {
        return chainMapper.selectVoById(id);
    }

    @Override
    public PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery, Long userId) {
        LambdaQueryWrapper<DatabusChain> lqw = Wrappers.lambdaQuery();
        // 列表白名单：排除 canvas_data / el_expression 两个大字段（列表零消费），
        // 保留 cmp_property 供卡片迷你拓扑预览递归，模板三字段供模板卡角标/说明/排序。
        // queryById 保持全量（编辑器加载走该接口）。
        lqw.select(DatabusChain::getId, DatabusChain::getChainCode, DatabusChain::getChainName,
            DatabusChain::getVersion, DatabusChain::getStatus, DatabusChain::getCmpProperty,
            DatabusChain::getDirectoryId,
            DatabusChain::getLogLevel, DatabusChain::getRemark,
            DatabusChain::getIsTemplate, DatabusChain::getTemplateDesc, DatabusChain::getTemplateSort,
            DatabusChain::getCreateTime, DatabusChain::getUpdateTime);
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusChain::getChainCode, bo.getChainCode());
        lqw.like(StringUtils.isNotBlank(bo.getChainName()), DatabusChain::getChainName, bo.getChainName());
        if (StringUtils.isNotBlank(bo.getStatus())) {
            lqw.eq(DatabusChain::getStatus, bo.getStatus());
        }
        // 目录归属过滤（链路工作台树按目录拉链用；null 不追加条件）
        lqw.eq(bo.getDirectoryId() != null, DatabusChain::getDirectoryId, bo.getDirectoryId());
        // 资源树按人隔离：onlyMine 只看自己创建的链路（超级管理员豁免，见 admin 视角全量）
        if (Boolean.TRUE.equals(bo.getOnlyMine()) && !LoginHelper.isSuperAdmin(userId)) {
            lqw.eq(DatabusChain::getCreateBy, userId);
        }
        // 双 tab 分流：'1' 精选模板库，'0' 我的链路（排除模板）；null 不追加条件，兼容内部调用
        boolean templateTab = TEMPLATE_FLAG_YES.equals(bo.getIsTemplate());
        if (StringUtils.isNotBlank(bo.getIsTemplate())) {
            lqw.eq(DatabusChain::getIsTemplate, bo.getIsTemplate());
        }
        if (templateTab) {
            // 模板库：手动排序升序，同序按最近更新
            lqw.orderByAsc(DatabusChain::getTemplateSort)
                .orderByDesc(DatabusChain::getUpdateTime)
                .orderByDesc(DatabusChain::getId);
        } else {
            lqw.orderByDesc(DatabusChain::getUpdateTime)
                .orderByDesc(DatabusChain::getId);
        }
        Page<DatabusChainVo> result = chainMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public ChainStatsVo countByStatus() {
        long draft = countByStatus(ChainStatusEnum.DRAFT.getCode());
        long published = countByStatus(ChainStatusEnum.PUBLISHED.getCode());
        long offline = countByStatus(ChainStatusEnum.OFFLINE.getCode());
        ChainStatsVo vo = new ChainStatsVo();
        vo.setDraft(draft);
        vo.setPublished(published);
        vo.setOffline(offline);
        // 模板是样板不是生产资产，不计入任何状态计数与总数
        vo.setTotal(draft + published + offline);
        return vo;
    }

    /**
     * 按状态计数（delFlag 由 @TableLogic 自动过滤；精选模板恒为草稿但不计入业务计数）
     */
    private long countByStatus(String status) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getStatus, status)
            .eq(DatabusChain::getIsTemplate, TEMPLATE_FLAG_NO));
        return count == null ? 0L : count;
    }

    @Override
    public Boolean insertByBo(DatabusChainBo bo) {
        validateChainCodeUnique(bo);
        validateLogLevel(bo.getLogLevel());
        InputParamValidator.validateDefs(bo.getInputParams());
        DatabusChain add = MapstructUtils.convert(bo, DatabusChain.class);
        add.setId(null);
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
        // 新建即普通链路：模板标记只能经模板专用端点打在已有链路上
        add.setIsTemplate(TEMPLATE_FLAG_NO);
        add.setElExpression(generateEl(bo));
        // cmpProperty 由 MapstructUtils 按同名同类型直接拷贝；持久化时 TypeHandler 转 JSON
        boolean flag = chainMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(DatabusChainBo bo, Long userId) {
        DatabusChain existing = chainMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        // 编辑归属校验（P1 看得见配套）：审核者可从审核详情打开他人链路画布查看，
        // 只读态由前端禁保存，保存的硬拦截在服务端——create_by 非空且非本人拒改；
        // 系统行（create_by 空）不拦，避免误伤系统数据维护
        if (existing.getCreateBy() != null && !Objects.equals(existing.getCreateBy(), userId)) {
            throw new ServiceException("只能修改自己创建的链路");
        }
        validateChainCodeUnique(bo);
        validateLogLevel(bo.getLogLevel());
        InputParamValidator.validateDefs(bo.getInputParams());
        DatabusChain update = MapstructUtils.convert(bo, DatabusChain.class);
        // 版本/状态不接受编辑接口修改（发布流转走独立接口）；
        // 模板标记同理——标记/取消走模板专用端点，通用编辑不触碰三字段（updateById 默认不更新 null）
        update.setVersion(null);
        update.setStatus(null);
        update.setIsTemplate(null);
        update.setElExpression(generateEl(bo));
        return chainMapper.updateById(update) > 0;
    }

    /**
     * 由画布组件树生成 EL；组件树为空时返回 null（空画布不产出 EL）
     */
    private String generateEl(DatabusChainBo bo) {
        return generateEl(bo.getCmpProperty());
    }

    /**
     * 由画布组件树生成 EL；组件树为空时返回 null（空画布不产出 EL）
     */
    private String generateEl(CmpProperty cmpProperty) {
        if (cmpProperty == null) {
            return null;
        }
        ELInfo elInfo = expressGenerator.generateEL(cmpProperty);
        return elInfo == null ? null : elInfo.getElStr();
    }

    /**
     * 校验链路编码在未删除记录中唯一
     */
    private void validateChainCodeUnique(DatabusChainBo bo) {
        Long currentId = bo.getId() == null ? -1L : bo.getId();
        if (existsByChainCode(bo.getChainCode(), currentId)) {
            throw new ServiceException("链路编码'" + bo.getChainCode() + "'已存在");
        }
    }

    /**
     * 编码在未删除记录中是否已存在（excludeId 为排除自身的主键，复制新链路传 null）
     */
    private boolean existsByChainCode(String chainCode, Long excludeId) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, chainCode)
            .ne(excludeId != null, DatabusChain::getId, excludeId));
        return count != null && count > 0;
    }

    /**
     * 校验 log_level 取值合法（OFF/BASIC/FULL）；空值跳过校验由 DB 默认值兜底。
     */
    private void validateLogLevel(String logLevel) {
        if (StringUtils.isBlank(logLevel)) {
            return;
        }
        if (!LogLevelEnum.isValid(logLevel)) {
            throw new ServiceException("执行记录档位'" + logLevel + "'非法（合法值：OFF/BASIC/FULL）");
        }
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        // 先查后删：删除后需按 chainCode 清理 Rule-DB 残留规则（未发布的链路 lf_chain 无记录，remove 幂等无害）
        List<DatabusChain> chains = chainMapper.selectByIds(ids);
        // 删前引用保护（decisions §十四-10）：被外部链路引用的链不能删，否则留悬空引用、
        // 引用方发布/执行时才报错，故障点离删除操作太远
        validateNotChainReferenced(chains);
        boolean flag = chainMapper.deleteByIds(ids) > 0;
        if (flag) {
            for (DatabusChain chain : chains) {
                rulePublishService.removeChainQuietly(chain.getChainCode());
            }
            cleanupInstallRecords(ids);
        }
        return flag;
    }

    /**
     * 级联清安装血缘（2026-10-24）：本地链是市场物料的 FORK 安装副本时，直接删链要一并删
     * databus_install 记录并重算物料安装计数——否则「已安装」列表留悬空卡片，
     * 卡片「打开」按 localResourceId 开的是已删链，画布空白。
     * 与市场 uninstall 端点同口径，此处补「资源树直接删副本」这条绕过路径。
     */
    private void cleanupInstallRecords(Collection<Long> ids) {
        List<DatabusInstall> installs = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
            .in(DatabusInstall::getLocalResourceId, ids));
        if (installs.isEmpty()) {
            return;
        }
        installMapper.deleteByIds(installs.stream().map(DatabusInstall::getId).toList());
        // 受影响市场物料重算安装计数（与 MarketInstallService.refreshCounts 同口径）
        List<Long> itemIds = installs.stream()
            .map(DatabusInstall::getMarketItemId).filter(Objects::nonNull).distinct().toList();
        for (Long itemId : itemIds) {
            Long count = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getMarketItemId, itemId));
            DatabusMarketItem update = new DatabusMarketItem();
            update.setId(itemId);
            update.setInstallCount(count == null ? 0 : count.intValue());
            marketItemMapper.updateById(update);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
            && !ChainStatusEnum.OFFLINE.getCode().equals(status)) {
            throw new ServiceException("当前状态不允许发布（仅草稿/已下线可发布）");
        }
        // 子流程引用把关（decisions.md 第七章）：草稿期不拦（编排自由），发布这道关一次性校验——
        // ① 循环引用（A→B→A）成环则引擎会无限递归，只能靠运行时深度保险丝兜底，代价是一次失败执行；
        // ② 被引子链必须已发布，否则主链能发布成功、执行时引擎在 chainMap 里查不到子链直接报错，
        //    故障点离操作点太远，排查成本高
        validateChainReferences(chain);
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
        update.setStatus(ChainStatusEnum.PUBLISHED.getCode());
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
            .eq(DatabusMarketItem::getResourceType, MARKET_TYPE_CHAIN)
            .eq(DatabusMarketItem::getSourceId, chainId)
            .eq(DatabusMarketItem::getStatus, MARKET_STATUS_ONLINE)
            .set(DatabusMarketItem::getStatus, MARKET_STATUS_PENDING)
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
     * 本方法纯读不落库，调用点在 validateChainReferences 之后、Rule-DB 推送之前
     * （拒绝必须发生在任何变更前）。
     *
     * @param chain  待发布主链
     * @param userId 发布操作者（判「自己的/他人的」）
     * @return 引导清单＋他人未上架组件提示（两者都可为空）
     */
    private ChainPublishResultVo collectUnlistedClosure(DatabusChain chain, Long userId) {
        LinkedHashMap<String, DatabusChain> closure = collectChainClosure(chain.getChainCode());
        ChainPublishResultVo result = new ChainPublishResultVo();
        List<ChainPublishResultVo.GuideItemVo> guideItems = new ArrayList<>();
        List<String> otherUnlistedComponents = new ArrayList<>();

        // 链维度：市场行 in 查一次（同源 CHAIN item 至多一行，toMap 合并防御性取首行）
        List<Long> closureChainIds = closure.values().stream().map(DatabusChain::getId).toList();
        Map<Long, DatabusMarketItem> chainItemMap = closureChainIds.isEmpty() ? Map.of()
            : marketItemMapper.selectList(Wrappers.<DatabusMarketItem>lambdaQuery()
                    .eq(DatabusMarketItem::getResourceType, MARKET_TYPE_CHAIN)
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
                guide.setResourceType(MARKET_TYPE_CHAIN);
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
                        .eq(DatabusMarketItem::getResourceType, MARKET_TYPE_COMPONENT)
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
                    guide.setResourceType(MARKET_TYPE_COMPONENT);
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
            self.setResourceType(MARKET_TYPE_CHAIN);
            self.setSourceId(chain.getId());
            self.setName(chain.getChainName());
            guideItems.add(0, self);
        }
        result.setGuideItems(guideItems);
        result.setOtherUnlistedComponents(otherUnlistedComponents);
        return result;
    }

    @Override
    public Boolean offline(Long id) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (!ChainStatusEnum.PUBLISHED.getCode().equals(chain.getStatus())) {
            throw new ServiceException("当前状态不允许下线（仅已发布可下线）");
        }
        // 先改状态（下线立即可见），再移除 Rule-DB 规则；移除失败仅记日志不阻断——
        // 残留规则因状态已下线不会被执行（执行入口按状态校验），重新发布时被覆盖
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setStatus(ChainStatusEnum.OFFLINE.getCode());
        boolean flag = chainMapper.updateById(update) > 0;
        if (flag) {
            rulePublishService.removeChainQuietly(chain.getChainCode());
        }
        return flag;
    }

    /**
     * 删除前校验待删链路是否仍被其他链路引用（decisions §十四-10 删除治理统一：
     * 对齐连接删除 validateNotReferenced 先例，全系统一套规则）。
     * <p>
     * 两段式：cmp_property LIKE 粗筛缩小候选集（带引号防同前缀误伤），再内存里用
     * {@link #collectChainRefs(CmpProperty)} 精确核对（LIKE 无法区分 JSON 里的普通组件 id 与 CHAIN 引用）。
     * 同批互引不算外部引用——本批一起删的链路互相引用时，引用关系随删除消亡，允许删。
     *
     * @param chains 本批待删链路（selectByIds 的结果）
     */
    private void validateNotChainReferenced(List<DatabusChain> chains) {
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
            List<String> refs = collectChainRefs(candidate.getCmpProperty());
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

    /**
     * 发布前校验子流程（CHAIN）引用：沿组件树递归下钻，检出循环引用与不可执行的子链。
     * <p>
     * 采用 DFS 三色标记：{@code path} 为灰色（当前递归路径上，命中即成环）、{@code checked} 为黑色
     * （子图已完整校验通过，直接剪枝——菱形引用 A→B、A→C、B→D、C→D 时 D 只展开一次）。
     *
     * @param chain 待发布链路
     */
    private void validateChainReferences(DatabusChain chain) {
        List<String> path = new ArrayList<>();
        path.add(chain.getChainCode());
        walkChainReferences(chain.getCmpProperty(), path, new HashSet<>());
    }

    /**
     * 递归校验组件树里的全部 CHAIN 引用。
     *
     * @param root    组件树节点
     * @param path    灰色集：当前递归路径（自待发布链路起的 chainCode 序列）
     * @param checked 黑色集：已完整校验通过的子链 chainCode
     */
    private void walkChainReferences(CmpProperty root, List<String> path, Set<String> checked) {
        for (String refCode : collectChainRefs(root)) {
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
            if (!ChainStatusEnum.PUBLISHED.getCode().equals(sub.getStatus())) {
                throw new ServiceException("子流程'" + refCode + "'尚未发布，请先发布它再发布本链路");
            }
            if (sub.getCmpProperty() == null) {
                throw new ServiceException("子流程'" + refCode + "'未编排组件，请先补全它的编排");
            }
            path.add(refCode);
            walkChainReferences(sub.getCmpProperty(), path, checked);
            path.remove(path.size() - 1);
        }
    }

    /**
     * 收集组件树（含 condition 分支与全部 children）中的 CHAIN 引用编码，按出现顺序去重。
     *
     * @param root 组件树节点
     * @return 子链 chainCode 列表，无引用时为空列表
     */
    @Override
    public List<String> collectChainRefs(CmpProperty root) {
        List<String> refs = new ArrayList<>();
        collectChainRefs(root, refs);
        return refs;
    }

    private void collectChainRefs(CmpProperty node, List<String> refs) {
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
        collectChainRefs(node.getCondition(), refs);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectChainRefs(child, refs);
            }
        }
    }

    @Override
    public LinkedHashMap<String, DatabusChain> collectChainClosure(String rootChainCode) {
        DatabusChain root = chainMapper.selectOne(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, rootChainCode));
        if (root == null) {
            throw new ServiceException("链路'" + rootChainCode + "'不存在或已删除");
        }
        List<String> path = new ArrayList<>();
        path.add(rootChainCode);
        LinkedHashMap<String, DatabusChain> closure = new LinkedHashMap<>();
        collectClosure(root, path, new HashSet<>(), closure);
        // 主链最后入列（其全部子链已就位），与发布侧「子链先合法、主链后校验」同口径
        closure.put(root.getChainCode(), root);
        return closure;
    }

    /**
     * 闭包递归收集（decisions §十四-1 链路安装全 fork）：post-order——子树收齐后父链入列，
     * 被引用者先于引用者，安装复制按此顺序施工，主链重定向引用时子链副本已就位。
     * <p>
     * 三色标记与 {@link #walkChainReferences} 同法：{@code path} 灰色集防环并回显完整路径，
     * {@code collected} 黑色集菱形剪枝（B、C 同引 D，D 只展开一次）；四类拒绝文案与其逐字
     * 对齐（decisions §十四-4：安装闭包与发布校验双口径一致，漏收集静默错误降险）。
     *
     * @param chain     当前展开链路
     * @param path      当前递归路径（chainCode 序列，灰）
     * @param collected 闭包已收编 chainCode（黑，含子树完整展开的剪枝依据）
     * @param closure   闭包累积（key=chainCode，entry 顺序即复制顺序）
     */
    private void collectClosure(DatabusChain chain, List<String> path, Set<String> collected,
                                LinkedHashMap<String, DatabusChain> closure) {
        for (String refCode : collectChainRefs(chain.getCmpProperty())) {
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
            if (!ChainStatusEnum.PUBLISHED.getCode().equals(sub.getStatus())) {
                throw new ServiceException("子流程'" + refCode + "'尚未发布，请先发布它再发布本链路");
            }
            if (sub.getCmpProperty() == null) {
                throw new ServiceException("子流程'" + refCode + "'未编排组件，请先补全它的编排");
            }
            path.add(refCode);
            collectClosure(sub, path, collected, closure);
            path.remove(path.size() - 1);
            closure.put(refCode, sub);
            collected.add(refCode);
        }
    }

    @Override
    public Long copy(Long id, ChainCopyBo bo) {
        DatabusChain source = chainMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        String chainCode = StringUtils.trim(bo.getChainCode());
        if (existsByChainCode(chainCode, null)) {
            throw new ServiceException("链路编码'" + chainCode + "'已存在");
        }
        // 全新实体：不复制 id/审计字段，插入时由 MetaObjectHandler 自动填充
        DatabusChain add = new DatabusChain();
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
        add.setChainName(StringUtils.trim(bo.getChainName()));
        add.setChainCode(chainCode);
        // 画布/组件树/记录档位/入参登记原样复制；组件树中引用的连接器仅复制 connectionId（共享连接器，不复制其本身）
        add.setCanvasData(source.getCanvasData());
        add.setCmpProperty(source.getCmpProperty());
        add.setLogLevel(source.getLogLevel());
        add.setInputParams(source.getInputParams());
        add.setRemark(source.getRemark());
        // 有意剥离模板身份：副本一律普通链路（说明/排序不继承，new 实体为 null，标记显式置 0）
        add.setIsTemplate(TEMPLATE_FLAG_NO);
        // 草稿不推 Rule-DB；与新增草稿同口径，EL 由组件树实时生成而非复制源 EL 文本
        add.setElExpression(generateEl(source.getCmpProperty()));
        chainMapper.insert(add);
        // 雪花 id 回填，供「使用模板」复制后直跳编辑器
        return add.getId();
    }

    @Override
    public CopySuggestionVo getCopySuggestion(Long id) {
        DatabusChain source = chainMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        CopySuggestionVo vo = new CopySuggestionVo();
        vo.setChainName(buildCopyName(source.getChainName()));
        vo.setChainCode(buildSuggestedCode(source.getChainCode()));
        return vo;
    }

    @Override
    public Boolean markAsTemplate(Long id, TemplateMarkBo bo) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (ChainStatusEnum.PUBLISHED.getCode().equals(chain.getStatus())) {
            throw new ServiceException("当前链路已发布，请先下线后再设为精选模板");
        }
        Integer sort = bo.getTemplateSort() == null ? TEMPLATE_DEFAULT_SORT : bo.getTemplateSort();
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setIsTemplate(TEMPLATE_FLAG_YES);
        update.setTemplateDesc(bo.getTemplateDesc());
        update.setTemplateSort(sort);
        // 模板恒草稿：已下线链路转模板时回归草稿态（下线时 Rule-DB 规则已移除，无残留）
        update.setStatus(ChainStatusEnum.DRAFT.getCode());
        return chainMapper.updateById(update) > 0;
    }

    @Override
    public Boolean unmarkTemplate(Long id) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (!TEMPLATE_FLAG_YES.equals(chain.getIsTemplate())) {
            throw new ServiceException("该链路不是精选模板");
        }
        // 清空说明/排序需显式 set null：updateById 默认 NOT_NULL 策略不更新 null，故走 UpdateWrapper；
        // 不触碰 status——模板期间恒为草稿，取消后即普通草稿
        return chainMapper.update(null, Wrappers.<DatabusChain>lambdaUpdate()
            .set(DatabusChain::getIsTemplate, TEMPLATE_FLAG_NO)
            .set(DatabusChain::getTemplateDesc, null)
            .set(DatabusChain::getTemplateSort, TEMPLATE_DEFAULT_SORT)
            .eq(DatabusChain::getId, id)) > 0;
    }

    /**
     * 副本名称：源名称后加"副本"，长度超 100 截断
     */
    private String buildCopyName(String sourceName) {
        String name = sourceName + COPY_NAME_SUFFIX;
        return name.length() > CHAIN_NAME_MAX_LEN ? name.substring(0, CHAIN_NAME_MAX_LEN) : name;
    }

    /**
     * 副本编码建议值：源编码先剥离结尾既有 _N 尾缀（避免副本的副本变 xxx_2_2），
     * 再从 _2 起查库取首个未占用值。预留序号段长度保证总长不超 100。
     */
    private String buildSuggestedCode(String sourceCode) {
        String base = CODE_TRAILING_SEQ.matcher(sourceCode).replaceFirst("");
        if (StringUtils.isBlank(base)) {
            // 极端情况：源编码本身就是 "_2" 之类，剥离后为空，回退用源编码
            base = sourceCode;
        }
        // 预留最长序号段 "_9999"
        int suffixReserve = 1 + String.valueOf(COPY_CODE_SEQ_MAX).length();
        int maxBaseLen = CHAIN_CODE_MAX_LEN - suffixReserve;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        for (int seq = COPY_CODE_SEQ_START; seq <= COPY_CODE_SEQ_MAX; seq++) {
            String candidate = base + "_" + seq;
            if (!existsByChainCode(candidate, null)) {
                return candidate;
            }
        }
        throw new ServiceException("复制失败：无法生成唯一链路编码，请手工指定");
    }

}
