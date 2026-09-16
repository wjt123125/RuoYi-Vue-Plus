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
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.service.IDatabusChainService;
import org.springframework.stereotype.Service;

import java.util.Collection;

/**
 * 链路定义 Service 实现。
 * <p>
 * 阶段 1A 只维护草稿（version=1/status=0）；发布、版本递增、下线留待阶段 3 管理页。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusChainServiceImpl implements IDatabusChainService {

    /**
     * 草稿状态
     */
    private static final String STATUS_DRAFT = "0";

    /**
     * 草稿初始版本
     */
    private static final int DRAFT_VERSION = 1;

    private final DatabusChainMapper chainMapper;

    private final ExpressGenerator expressGenerator;

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
        DatabusChain add = MapstructUtils.convert(bo, DatabusChain.class);
        add.setId(null);
        add.setVersion(DRAFT_VERSION);
        add.setStatus(STATUS_DRAFT);
        add.setElExpression(generateEl(bo));
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
        DatabusChain update = MapstructUtils.convert(bo, DatabusChain.class);
        // 版本/状态不接受编辑接口修改（发布流转走阶段 3 独立接口）
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

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        return chainMapper.deleteByIds(ids) > 0;
    }

}
