package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.enums.ComponentStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.script.host.ScriptComponentRegistrar;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.dromara.databus.constant.ComponentConstants.DEPRECATE_NOTE_RETIRED;
import static org.dromara.databus.constant.ComponentConstants.DEPRECATED_NO;
import static org.dromara.databus.constant.ComponentConstants.DEPRECATED_YES;

/**
 * 组件元信息的定义写入：新建、编辑、启停、删除（含被引用件的软退役）。
 * <p>
 * 事务边界留在 {@link DatabusComponentServiceImpl}；本类只做单步落库与校验。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ComponentWriteSupport {

    private final DatabusComponentMapper componentMapper;

    private final DatabusChainMapper chainMapper;

    private final ScriptComponentRegistrar scriptRegistrar;

    private final ComponentDepSupport depSupport;

    /**
     * 新增组件元信息。
     *
     * @param bo 组件元信息业务对象
     * @return 是否新增成功
     */
    public Boolean insert(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent add = MapstructUtils.convert(bo, DatabusComponent.class);
        if (StringUtils.isBlank(add.getStatus())) {
            add.setStatus(ComponentStatusEnum.ENABLED.getCode());
        }
        if (StringUtils.isBlank(add.getDeprecated())) {
            add.setDeprecated(DEPRECATED_NO);
        }
        add.setOwnerType(OwnerTypeEnum.USER.getCode());
        boolean flag = componentMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
            // 写入点 2（material-market §15.12.7）：表单件也能选 CONNECTION_SELECT 控件，
            // 故新建时就要落依赖行，否则该组件停用连接器时的影响面反查恒为空
            depSupport.syncConnectorDeps(add.getComponentCode(), add.getParamSchema());
        }
        return flag;
    }

    /**
     * 修改组件元信息（不含启停状态）。
     *
     * @param bo 组件元信息业务对象
     * @return 是否修改成功
     */
    public Boolean update(DatabusComponentBo bo) {
        validateComponentCodeUnique(bo);
        DatabusComponent update = MapstructUtils.convert(bo, DatabusComponent.class);
        boolean scriptOwned = protectScriptContractColumns(bo.getId(), update);
        // 启停拆到独立权限点（databus:component:toggle）：治理编辑不得顺手改 status，
        // 否则拆分形同虚设（MP updateById 默认 NOT_NULL 策略，置 null 即跳过该列）
        update.setStatus(null);
        boolean flag = componentMapper.updateById(update) > 0;
        if (flag && !scriptOwned) {
            // 写入点 3（§15.12.7）：必须与 protectScriptContractColumns 同一个判断——
            // 库存脚本件的契约列（含 param_schema）以脚本编译物化为正本，治理编辑既动不了 schema，
            // 也就不许动由它派生的依赖行，那些行归脚本保存管线（updateScriptColumns）维护
            depSupport.syncConnectorDeps(update.getComponentCode(), update.getParamSchema());
        }
        return flag;
    }

    /**
     * 启停组件（只接受 ENABLED / DISABLED）。
     *
     * @param id     组件主键
     * @param status 目标状态
     * @return 是否修改成功
     */
    public Boolean updateStatus(Long id, String status) {
        if (!ComponentStatusEnum.ENABLED.getCode().equals(status)
            && !ComponentStatusEnum.DISABLED.getCode().equals(status)) {
            throw new ServiceException("启停状态只能是 ENABLED（启用）或 DISABLED（停用）");
        }
        DatabusComponent existing = componentMapper.selectById(id);
        if (existing == null) {
            throw new ServiceException("组件不存在：" + id);
        }
        if (ComponentStatusEnum.DRAFT.getCode().equals(existing.getStatus())) {
            throw new ServiceException("草稿组件没有启停概念，请先采纳或删除");
        }
        return componentMapper.update(null, Wrappers.<DatabusComponent>lambdaUpdate()
            .set(DatabusComponent::getStatus, status)
            .eq(DatabusComponent::getId, id)) > 0;
    }

    /**
     * 批量删除：被链路引用的转软退役，未引用的真删并清理脚本宿主与依赖行。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    public Boolean delete(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        List<DatabusComponent> rows = componentMapper.selectByIds(ids);
        // 引用分流（§十二-5）：被链路引用的转软退役（deprecated=1 不物理删——市场安装者共享同一份
        // 组件，存量链路按 code 引用须继续可运行）；未引用的真删 + 清理脚本宿主。
        // 必须真删：唯一键 uk_component_code 不含 del_flag，逻辑删留下的行会让该编码永久不可复用
        Set<Long> retireIds = findReferencedIds(rows);
        List<Long> removeIds = rows.stream()
            .map(DatabusComponent::getId)
            .filter(id -> !retireIds.contains(id))
            .toList();
        boolean removed = false;
        if (!removeIds.isEmpty()) {
            List<String> codes = rows.stream()
                .filter(row -> removeIds.contains(row.getId()))
                .map(DatabusComponent::getComponentCode)
                .filter(StringUtils::isNotBlank)
                .toList();
            removed = componentMapper.deletePhysicallyByIds(removeIds) > 0;
            if (removed) {
                // 清理脚本宿主引用（FlowBus 节点不主动摘除：硬删件残留节点无执行入口，重启消失；
                // 软退役件保留宿主，存量链路继续运行）
                codes.forEach(scriptRegistrar::forget);
                // 写入点 4（§15.12.7）：依赖行随硬删一起清；软退役（deprecated=1）分支**必须留着**——
                // 老链还在跑、还在用那个连接器，停用连接器时的影响面反查得算上它们
                depSupport.deleteByCodes(codes);
            }
        }
        if (!retireIds.isEmpty()) {
            componentMapper.update(null, Wrappers.<DatabusComponent>lambdaUpdate()
                .set(DatabusComponent::getDeprecated, DEPRECATED_YES)
                .set(DatabusComponent::getDeprecateNote, DEPRECATE_NOTE_RETIRED)
                .in(DatabusComponent::getId, retireIds));
        }
        return removed || !retireIds.isEmpty();
    }

    /**
     * 库存脚本件（script_body 非空）的契约四列以脚本编译物化结果为正本，
     * 治理编辑（databus:component:edit）不得覆盖；仅脚本保存管线可写。
     * MP updateById 默认 NOT_NULL 策略，置 null 即跳过这些列。
     *
     * @return true 表示本行是库存脚本件、契约列已被保护（调用方据此同样跳过依赖行同步，§15.12.7-3）
     */
    private boolean protectScriptContractColumns(Long id, DatabusComponent update) {
        if (id == null) {
            return false;
        }
        DatabusComponent existing = componentMapper.selectById(id);
        if (existing == null || StringUtils.isBlank(existing.getScriptBody())) {
            return false;
        }
        update.setNodeType(null);
        update.setEditor(null);
        update.setParamSchema(null);
        update.setDataExample(null);
        return true;
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

    /**
     * 引用检测（§十二-5 软退役判据，沿用原删除校验口径）：返回仍被未删除链路引用的组件 id 集合。
     * <p>链路 cmp_property 中节点形态为 {@code {"id":"<componentCode>","type":"NodeComponent",...}}，
     * 在 JSON 文本中匹配完整片段 {@code "id":"<编码>"}（引号闭合避免短编码子串误匹配）；
     * 逐码 selectCount 以定位具体被引用的组件。
     */
    private Set<Long> findReferencedIds(List<DatabusComponent> rows) {
        Set<Long> referencedIds = new HashSet<>();
        for (DatabusComponent row : rows) {
            String code = row.getComponentCode();
            if (StringUtils.isBlank(code)) {
                continue;
            }
            Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .like(DatabusChain::getCmpProperty, referenceLikePattern(code)));
            if (count != null && count > 0) {
                referencedIds.add(row.getId());
            }
        }
        return referencedIds;
    }

    /** cmp_property 中的精确引用片段（已转义 LIKE 通配符），外层由 MP 包裹 % 匹配 */
    private String referenceLikePattern(String componentCode) {
        return "\"id\":\"" + escapeLike(componentCode) + "\"";
    }

    /** 转义 MySQL LIKE 通配符（MySQL 默认转义符为反斜杠，无需显式 ESCAPE 子句） */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }

}
