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
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusChain::getChainCode, bo.getChainCode());
        lqw.like(StringUtils.isNotBlank(bo.getChainName()), DatabusChain::getChainName, bo.getChainName());
        lqw.orderByDesc(DatabusChain::getUpdateTime)
            .orderByDesc(DatabusChain::getId);
        Page<DatabusChainVo> result = chainMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
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
        if (bo.getCmpProperty() == null) {
            return null;
        }
        ELInfo elInfo = expressGenerator.generateEL(bo.getCmpProperty());
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

}
