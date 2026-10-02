package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.component.schema.registry.ComponentSchemaRegistry;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.ComponentOptionVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 组件元信息 Service 实现
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DatabusComponentServiceImpl implements IDatabusComponentService {

    /**
     * 启用状态
     */
    private static final String STATUS_ENABLED = "0";

    private final DatabusComponentMapper componentMapper;

    private final ComponentSchemaRegistry componentSchemaRegistry;

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
    public ComponentOptionsVo queryOptions() {
        // 1. 内置注解件（注册中心已按 sort、code 排序）
        List<ComponentOptionVo> options = componentSchemaRegistry.all().stream()
            .map(ComponentOptionVo::ofSystem)
            .collect(Collectors.toCollection(ArrayList::new));
        Set<String> builtinCodes = options.stream()
            .map(ComponentOptionVo::code)
            .collect(Collectors.toSet());

        // 2. 自定义启用行（按 id），code 冲突内置优先、DB 行丢弃
        List<DatabusComponent> rows = componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getStatus, STATUS_ENABLED)
            .orderByAsc(DatabusComponent::getId));
        for (DatabusComponent row : rows) {
            if (builtinCodes.contains(row.getComponentCode())) {
                log.warn("[databus-schema] 自定义物料 code 与内置件冲突，DB 行丢弃 code={}, id={}",
                    row.getComponentCode(), row.getId());
                continue;
            }
            ComponentOptionVo parsed = parseCustomOption(row.getParamSchema(), row.getComponentCode(), row.getId());
            options.add(ComponentOptionVo.ofCustom(parsed,
                row.getComponentCode(), row.getComponentName(), row.getIcon(), row.getDescription()));
        }
        return ComponentOptionsVo.of(options);
    }

    /**
     * 解析自定义行 param_schema（同构 JSON）；空或解析失败返回 null，
     * 调用方降级为只有身份元信息的选项（前端回退 JSON 编辑器，不阻断 /options）。
     */
    private ComponentOptionVo parseCustomOption(String paramSchema, String code, Long id) {
        if (StringUtils.isBlank(paramSchema)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(paramSchema, ComponentOptionVo.class);
        } catch (Exception e) {
            log.warn("[databus-schema] 自定义物料 param_schema 解析失败，降级仅元信息 code={}, id={}, err={}",
                code, id, e.getMessage());
            return null;
        }
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
