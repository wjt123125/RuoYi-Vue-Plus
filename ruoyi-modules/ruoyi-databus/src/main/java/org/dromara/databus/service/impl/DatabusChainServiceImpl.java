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
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.ChainStatsVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.LogLevelEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

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
     * 链路名称/编码最大长度（与 Bo @Size 约束一致）
     */
    private static final int CHAIN_NAME_MAX_LEN = 100;
    private static final int CHAIN_CODE_MAX_LEN = 100;

    /**
     * 副本名称后缀
     */
    private static final String COPY_NAME_SUFFIX = "副本";

    /**
     * 副本编码后缀（后再追加 6 位十六进制随机串）
     */
    private static final String COPY_CODE_SUFFIX = "_copy";

    /**
     * 随机段固定 6 位十六进制（0x100000 ≤ n < 0x1000000）
     */
    private static final int COPY_RANDOM_SUFFIX_LEN = 6;
    private static final int COPY_RANDOM_LOWER = 0x100000;
    private static final int COPY_RANDOM_UPPER = 0x1000000;

    /**
     * 生成唯一编码的最大尝试次数
     */
    private static final int COPY_CODE_MAX_ATTEMPTS = 10;

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
        // 保留 cmp_property 供卡片迷你拓扑预览递归。queryById 保持全量（编辑器加载走该接口）。
        lqw.select(DatabusChain::getId, DatabusChain::getChainCode, DatabusChain::getChainName,
            DatabusChain::getVersion, DatabusChain::getStatus, DatabusChain::getCmpProperty,
            DatabusChain::getLogLevel, DatabusChain::getRemark,
            DatabusChain::getCreateTime, DatabusChain::getUpdateTime);
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusChain::getChainCode, bo.getChainCode());
        lqw.like(StringUtils.isNotBlank(bo.getChainName()), DatabusChain::getChainName, bo.getChainName());
        if (StringUtils.isNotBlank(bo.getStatus())) {
            lqw.eq(DatabusChain::getStatus, bo.getStatus());
        }
        lqw.orderByDesc(DatabusChain::getUpdateTime)
            .orderByDesc(DatabusChain::getId);
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
        vo.setTotal(draft + published + offline);
        return vo;
    }

    /**
     * 按状态计数（delFlag 由 @TableLogic 自动过滤）
     */
    private long countByStatus(String status) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getStatus, status));
        return count == null ? 0L : count;
    }

    @Override
    public Boolean insertByBo(DatabusChainBo bo) {
        validateChainCodeUnique(bo);
        validateLogLevel(bo.getLogLevel());
        DatabusChain add = MapstructUtils.convert(bo, DatabusChain.class);
        add.setId(null);
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
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
        DatabusChain update = MapstructUtils.convert(bo, DatabusChain.class);
        // 版本/状态不接受编辑接口修改（发布流转走独立接口）
        update.setVersion(null);
        update.setStatus(null);
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
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, bo.getChainCode())
            .ne(DatabusChain::getId, currentId));
        if (count != null && count > 0) {
            throw new ServiceException("链路编码'" + bo.getChainCode() + "'已存在");
        }
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
    public Boolean copy(Long id) {
        DatabusChain source = chainMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        // 全新实体：不复制 id/审计字段，插入时由 MetaObjectHandler 自动填充
        DatabusChain add = new DatabusChain();
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
        add.setChainName(buildCopyName(source.getChainName()));
        add.setChainCode(buildCopyCode(source.getChainCode()));
        // 画布/组件树/记录档位原样复制；组件树中引用的连接器仅复制 connectionId（共享连接器，不复制其本身）
        add.setCanvasData(source.getCanvasData());
        add.setCmpProperty(source.getCmpProperty());
        add.setLogLevel(source.getLogLevel());
        add.setRemark(source.getRemark());
        // 草稿不推 Rule-DB；与新增草稿同口径，EL 由组件树实时生成而非复制源 EL 文本
        add.setElExpression(generateEl(source.getCmpProperty()));
        return chainMapper.insert(add) > 0;
    }

    /**
     * 副本名称：源名称后加"副本"，长度超 100 截断
     */
    private String buildCopyName(String sourceName) {
        String name = sourceName + COPY_NAME_SUFFIX;
        return name.length() > CHAIN_NAME_MAX_LEN ? name.substring(0, CHAIN_NAME_MAX_LEN) : name;
    }

    /**
     * 副本编码：源编码 + _copy + 随机短串，循环校验直到唯一（最多 10 次）
     */
    private String buildCopyCode(String sourceCode) {
        String base = sourceCode + COPY_CODE_SUFFIX;
        // 预留随机段长度（_ + 6 位十六进制），保证总长度不超过 100
        int maxBaseLen = CHAIN_CODE_MAX_LEN - COPY_RANDOM_SUFFIX_LEN - 1;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        for (int i = 0; i < COPY_CODE_MAX_ATTEMPTS; i++) {
            String candidate = base + "_" + Integer.toHexString(
                ThreadLocalRandom.current().nextInt(COPY_RANDOM_LOWER, COPY_RANDOM_UPPER));
            Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getChainCode, candidate));
            if (count == null || count == 0) {
                return candidate;
            }
        }
        throw new ServiceException("复制失败：无法生成唯一链路编码，请重试");
    }

}
