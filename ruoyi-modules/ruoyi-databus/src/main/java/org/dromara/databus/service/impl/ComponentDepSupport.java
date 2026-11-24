package org.dromara.databus.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.databus.component.schema.enums.WidgetKind;
import org.dromara.databus.component.schema.model.PropSchema;
import org.dromara.databus.domain.DatabusComponentConnectorDep;
import org.dromara.databus.domain.vo.ComponentSchemaBody;
import org.dromara.databus.mapper.DatabusComponentConnectorDepMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 组件 param_schema → 连接器依赖行（{@code databus_component_connector_dep}）的派生写入。
 * <p>
 * 本表不是独立事实源，只是 {@code param_schema} 的派生索引：对外只提供「按 code 全量重写」，
 * 由 {@link ComponentWriteSupport}（表单件新建/编辑/硬删）与 {@link ComponentScriptSupport}
 * （脚本件保存/回滚）在不同写入点调用。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ComponentDepSupport {

    private final DatabusComponentConnectorDepMapper connectorDepMapper;

    /**
     * 解析自定义行 param_schema 契约缓存列（新形态只装 schema 体 <code>{"fields":[...]}</code>）；
     * 空或解析失败返回 null，调用方降级为无 schema 的选项（前端回退 JSON 编辑器，不阻断 /options）。
     */
    public ComponentSchemaBody parseSchemaBody(String paramSchema, String code, Long id) {
        if (StringUtils.isBlank(paramSchema)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(paramSchema, ComponentSchemaBody.class);
        } catch (Exception e) {
            log.warn("[databus-schema] 自定义物料 param_schema 解析失败，降级无 schema code={}, id={}, err={}",
                code, id, e.getMessage());
            return null;
        }
    }

    /**
     * 从 param_schema 原文同步组件↔连接器依赖行（表单件的两个写入点用）。
     *
     * <p>paramSchema 为空时**不动依赖行**：MP updateById 是 NOT_NULL 策略，空值本来也不会写库，
     * 此时删依赖行会让关系表与库中仍在的 schema 脱节。
     * 解析失败同样不动（沿用 {@link #parseSchemaBody} 的降级口径）——宁可留一份可能过期的依赖，
     * 也不要在 schema 读不懂时把「停用连接器影响哪些组件」的反查静默清空。
     */
    public void syncConnectorDeps(String componentCode, String paramSchema) {
        if (StringUtils.isBlank(componentCode) || StringUtils.isBlank(paramSchema)) {
            return;
        }
        ComponentSchemaBody body = parseSchemaBody(paramSchema, componentCode, null);
        if (body != null) {
            writeConnectorDeps(componentCode, body.fields());
        }
    }

    /**
     * 按 code 先删后插重写依赖行（§15.12.7 写入逻辑）。
     *
     * <p>本表是 {@code param_schema} 的**派生索引**，不是独立事实源，故用「全量重写」而非差量比对：
     * 一个组件的依赖族通常 0~1 行，先删后插比算差集更短也更不容易漏。
     * 四个调用点都在已有事务内，本方法不另开事务。
     */
    public void writeConnectorDeps(String componentCode, List<PropSchema> fields) {
        if (StringUtils.isBlank(componentCode)) {
            return;
        }
        connectorDepMapper.deleteByCodes(List.of(componentCode));
        Set<String> families = collectConnectorFamilies(fields);
        if (families.isEmpty()) {
            return;
        }
        connectorDepMapper.insertBatch(families.stream()
            .map(family -> {
                DatabusComponentConnectorDep dep = new DatabusComponentConnectorDep();
                dep.setComponentCode(componentCode);
                dep.setConnectorFamily(family);
                return dep;
            })
            .toList());
    }

    /** 按组件编码清依赖行（组件硬删时调用）。 */
    public void deleteByCodes(List<String> codes) {
        connectorDepMapper.deleteByCodes(codes);
    }

    /**
     * 收集 schema 字段树里声明了连接器能力族的字段，族去重（保持首次出现顺序）。
     *
     * <p>必须递归下钻 {@code fields}：CONNECTION_SELECT 可以放在 OBJECT 分组或 OBJECT_ROWS
     * 行编辑里（{@code PropSchema.fields} 正是为此存在）。只扫顶层会漏依赖，而漏了不报错——
     * 只是停用连接器时影响面数字偏小，属于静默错，比抛异常更难查。
     */
    private Set<String> collectConnectorFamilies(List<PropSchema> fields) {
        Set<String> families = new LinkedHashSet<>();
        collectConnectorFamilies(fields, families);
        return families;
    }

    private void collectConnectorFamilies(List<PropSchema> fields, Set<String> out) {
        if (fields == null) {
            return;
        }
        for (PropSchema field : fields) {
            if (field == null) {
                continue;
            }
            if (WidgetKind.CONNECTION_SELECT == field.widget() && StringUtils.isNotBlank(field.connectorFamily())) {
                out.add(field.connectorFamily());
            }
            collectConnectorFamilies(field.fields(), out);
        }
    }

}
