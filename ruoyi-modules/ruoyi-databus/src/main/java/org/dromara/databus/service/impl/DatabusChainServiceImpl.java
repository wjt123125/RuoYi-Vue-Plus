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
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.ChainCopyBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.TemplateMarkBo;
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
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

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

    private final DatabusChainMapper chainMapper;

    private final ExpressGenerator expressGenerator;

    private final RulePublishService rulePublishService;

    @Override
    public DatabusChainVo queryById(Long id) {
        return chainMapper.selectVoById(id);
    }

    @Override
    public PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusChain> lqw = Wrappers.lambdaQuery();
        // 列表白名单：排除 canvas_data / el_expression 两个大字段（列表零消费），
        // 保留 cmp_property 供卡片迷你拓扑预览递归，模板三字段供模板卡角标/说明/排序。
        // queryById 保持全量（编辑器加载走该接口）。
        lqw.select(DatabusChain::getId, DatabusChain::getChainCode, DatabusChain::getChainName,
            DatabusChain::getVersion, DatabusChain::getStatus, DatabusChain::getCmpProperty,
            DatabusChain::getLogLevel, DatabusChain::getRemark,
            DatabusChain::getIsTemplate, DatabusChain::getTemplateDesc, DatabusChain::getTemplateSort,
            DatabusChain::getCreateTime, DatabusChain::getUpdateTime);
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusChain::getChainCode, bo.getChainCode());
        lqw.like(StringUtils.isNotBlank(bo.getChainName()), DatabusChain::getChainName, bo.getChainName());
        if (StringUtils.isNotBlank(bo.getStatus())) {
            lqw.eq(DatabusChain::getStatus, bo.getStatus());
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
    public Boolean updateByBo(DatabusChainBo bo) {
        DatabusChain existing = chainMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("链路不存在或已删除");
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
        boolean flag = chainMapper.deleteByIds(ids) > 0;
        if (flag) {
            for (DatabusChain chain : chains) {
                rulePublishService.removeChainQuietly(chain.getChainCode());
            }
        }
        return flag;
    }

    @Override
    public Boolean publish(Long id) {
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
        // 先推 Rule-DB（脚本冲突/引擎异常时状态不变，重试即重推；推成功而后续状态更新失败
        // 会留孤儿规则，重发时被 UPSERT 覆盖，无害）
        rulePublishService.publishChain(chain.getChainCode(), elExpression, cmpProperty);
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setStatus(ChainStatusEnum.PUBLISHED.getCode());
        update.setVersion(chain.getVersion() + 1);
        // 回写实时生成的 EL，保持表内字段与组件树一致（add/update 也是这口径）
        update.setElExpression(elExpression);
        return chainMapper.updateById(update) > 0;
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
