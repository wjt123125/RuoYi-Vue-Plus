package org.dromara.databus.service.support.component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.component.schema.model.CmpSchema;
import org.dromara.databus.component.schema.registry.ComponentSchemaRegistry;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusComponentDomain;
import org.dromara.databus.domain.DatabusComponentGroup;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.mapper.DatabusComponentDomainMapper;
import org.dromara.databus.mapper.DatabusComponentGroupMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.dromara.databus.constant.CommonConstants.FLAG_YES;
import static org.dromara.databus.constant.ComponentConstants.DEPRECATED_NO;

/**
 * 组件元信息的读接口：单条/分页、编辑器物料合流（/options）、分组与业务域字典。
 * <p>
 * 只被 {@link DatabusComponentServiceImpl} 调用，纯读不落库。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ComponentQuerySupport {

    private final DatabusComponentMapper componentMapper;

    private final DatabusComponentGroupMapper componentGroupMapper;

    private final DatabusComponentDomainMapper componentDomainMapper;

    private final ComponentSchemaRegistry componentSchemaRegistry;

    private final ComponentConnectorDepSupport depSupport;

    /**
     * 单条加载。
     *
     * @param id 主键
     * @return 组件元信息视图对象
     */
    public DatabusComponentVo queryById(Long id) {
        return componentMapper.selectVoById(id);
    }

    /**
     * 列表分页。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @return 分页结果
     */
    public PageResult<DatabusComponentVo> queryPageList(DatabusComponentBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<DatabusComponent> lqw = buildQueryWrapper(bo);
        Page<DatabusComponentVo> result = componentMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    /**
     * 编辑器物料合流选项（/options）。
     *
     * @return 带 schemaVersion 的合流响应
     */
    public ComponentOptionsVo queryOptions() {
        // 同码合流新秩序（2026-10-06 脚本宿主立法反转）：
        // ①同码启用且 script_body 非空＝库存脚本件，全量取 DB（含同码覆盖内置 jar 件）；
        // ②同码启用无 body＝治理覆盖行，治理取 DB、契约取内置注解（OVERLAY）；
        // ③无 DB 行＝纯内置件（SYSTEM）。「编码冲突丢弃 DB」策略废除。
        Map<String, CmpSchema> builtin = componentSchemaRegistry.all().stream()
            .collect(Collectors.toMap(CmpSchema::code, schema -> schema,
                (a, b) -> a, LinkedHashMap::new));

        List<DatabusComponent> rows = componentMapper.selectList(Wrappers.<DatabusComponent>lambdaQuery()
            .eq(DatabusComponent::getStatus, ComponentStatusEnum.ENABLED.getCode())
            // 软退役件（deprecated=1）停止投放：存量链路照常运行，新画布拖不进，树侧据此红标缺依赖
            .eq(DatabusComponent::getDeprecated, DEPRECATED_NO)
            .orderByAsc(DatabusComponent::getSort)
            .orderByAsc(DatabusComponent::getId));

        Set<String> dbOwnedCodes = new HashSet<>();
        List<ComponentOptionVo> dbOptions = new ArrayList<>();
        for (DatabusComponent row : rows) {
            dbOwnedCodes.add(row.getComponentCode());
            CmpSchema sysSchema = builtin.get(row.getComponentCode());
            if (StringUtils.isBlank(row.getScriptBody()) && sysSchema != null) {
                dbOptions.add(ComponentOptionVo.ofOverlay(sysSchema, row));
            } else {
                dbOptions.add(ComponentOptionVo.ofCustom(row, depSupport.parseSchemaBody(row.getParamSchema(),
                    row.getComponentCode(), row.getId())));
            }
        }

        List<ComponentOptionVo> options = new ArrayList<>();
        builtin.forEach((code, schema) -> {
            if (!dbOwnedCodes.contains(code)) {
                options.add(ComponentOptionVo.ofSystem(schema));
            }
        });
        options.addAll(dbOptions);
        return ComponentOptionsVo.of(options);
    }

    /**
     * 物料面板分组字典（sort asc, id asc）。
     *
     * @return 分组字典列表
     */
    public List<ComponentGroupVo> queryGroups() {
        // 手动装配而非 selectVoList：VO 字段名（key/label）与实体属性名（groupKey/groupName）
        // 不一致，mapstruct-plus 的属性名映射拷不上
        return componentGroupMapper.selectList(Wrappers.<DatabusComponentGroup>lambdaQuery()
                .orderByAsc(DatabusComponentGroup::getSort)
                .orderByAsc(DatabusComponentGroup::getId))
            .stream()
            .map(row -> new ComponentGroupVo(row.getGroupKey(), row.getGroupName(), row.getColor(), row.getSort()))
            .toList();
    }

    /**
     * 物料业务域字典（sort asc, id asc）。
     *
     * @return 业务域字典列表
     */
    public List<ComponentDomainVo> queryDomains() {
        // 同上手动装配；is_default 的 char(1) Y/N 在此收敛为 Boolean
        return componentDomainMapper.selectList(Wrappers.<DatabusComponentDomain>lambdaQuery()
                .orderByAsc(DatabusComponentDomain::getSort)
                .orderByAsc(DatabusComponentDomain::getId))
            .stream()
            .map(row -> new ComponentDomainVo(row.getDomainKey(), row.getDomainName(), row.getColor(),
                row.getSort(), FLAG_YES.equals(row.getIsDefault())))
            .toList();
    }

    private LambdaQueryWrapper<DatabusComponent> buildQueryWrapper(DatabusComponentBo bo) {
        LambdaQueryWrapper<DatabusComponent> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getComponentCode()), DatabusComponent::getComponentCode, bo.getComponentCode());
        lqw.like(StringUtils.isNotBlank(bo.getComponentName()), DatabusComponent::getComponentName, bo.getComponentName());
        lqw.eq(StringUtils.isNotBlank(bo.getCategory()), DatabusComponent::getCategory, bo.getCategory());
        if (StringUtils.isBlank(bo.getStatus())) {
            // 草稿组件未采纳，默认不出现在组件管理列表
            lqw.ne(DatabusComponent::getStatus, ComponentStatusEnum.DRAFT.getCode());
        } else {
            lqw.eq(DatabusComponent::getStatus, bo.getStatus());
            if (ComponentStatusEnum.DRAFT.getCode().equals(bo.getStatus())) {
                // 草稿只有发起人可见：所有权决定「谁能改」，不能出现在别人的列表里
                lqw.eq(DatabusComponent::getCreateBy, LoginHelper.getUserId());
            }
        }
        lqw.orderByAsc(DatabusComponent::getCategory)
            .orderByDesc(DatabusComponent::getCreateTime);
        return lqw;
    }

}
