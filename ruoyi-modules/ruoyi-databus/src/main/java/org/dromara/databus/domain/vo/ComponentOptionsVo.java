package org.dromara.databus.domain.vo;

import java.util.List;

import static org.dromara.databus.constant.ComponentConstants.CURRENT_SCHEMA_VERSION;

/**
 * /options 响应包络：带 schemaVersion 应对契约演进（schema JSON 契约 §5.1）。
 *
 * @param schemaVersion schema 契约版本（当前固定 1）
 * @param components    合流后的物料列表（内置在前按 sort，自定义在后按 id）
 * @author databus
 */
public record ComponentOptionsVo(int schemaVersion, List<ComponentOptionVo> components) {

    public ComponentOptionsVo {
        components = components == null ? List.of() : List.copyOf(components);
    }

    public static ComponentOptionsVo of(List<ComponentOptionVo> components) {
        return new ComponentOptionsVo(CURRENT_SCHEMA_VERSION, components);
    }
}
