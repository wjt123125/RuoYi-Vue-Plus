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
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 组件元信息 Service 实现
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusComponentServiceImpl implements IDatabusComponentService {

    /**
     * 启用状态
     */
    private static final String STATUS_ENABLED = "0";

    private final DatabusComponentMapper componentMapper;

    @Override
    public DatabusComponentVo queryById(Long id) {
        return componentMapper.selectVoById(id);
    }

    @Override
    public PageResult<DatabusComponentVo> queryPageList(DatabusComponentBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusComponent> lqw = buildQueryWrapper(bo);
        Page<DatabusComponentVo> result = componentMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    @Override
    public List<DatabusComponentVo> queryEnabledList() {
        LambdaQueryWrapper<DatabusComponent> lqw = Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getStatus, STATUS_ENABLED)
            .orderByAsc(DatabusComponent::getCategory)
            .orderByAsc(DatabusComponent::getId);
        return componentMapper.selectVoList(lqw);
    }

    private LambdaQueryWrapper<DatabusComponent> buildQueryWrapper(DatabusComponentBo bo) {
        LambdaQueryWrapper<DatabusComponent> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getComponentCode()), DatabusComponent::getComponentCode, bo.getComponentCode());
        lqw.like(StringUtils.isNotBlank(bo.getComponentName()), DatabusComponent::getComponentName, bo.getComponentName());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), DatabusComponent::getCategory, bo.getCategory());
        lqw.eq(StringUtils.isNotBlank(bo.getStatus()), DatabusComponent::getStatus, bo.getStatus());
        lqw.orderByAsc(DatabusComponent::getCategory)
            .orderByDesc(DatabusComponent::getCreateTime);
        return lqw;
    }

    @Override
    public Boolean insertByBo(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent add = MapstructUtils.convert(bo, DatabusComponent.class);
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(STATUS_ENABLED);
        }
        boolean flag = componentMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent update = MapstructUtils.convert(bo, DatabusComponent.class);
        return componentMapper.updateById(update) > 0;
    }

    /**
     * 校验组件编码在未删除记录中唯一
     */
    private void validateComponentCodeUnique(DatabusComponentBo bo) {
        Long currentId = bo.getId() == null ? -1L : bo.getId();
        Long count = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getComponentCode, bo.getComponentCode())
            .ne(DatabusComponent::getId, currentId));
        if (count != null && count > 0) {
            throw new ServiceException("组件编码'" + bo.getComponentCode() + "'已存在");
        }
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        return componentMapper.deleteByIds(ids) > 0;
    }

}
