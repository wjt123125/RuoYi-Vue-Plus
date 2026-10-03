package org.dromara.databus.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.model.CmpSchema;

/**
 * 物料合流选项（/options 单项，schema JSON 契约 §5.1）。
 * <p>
 * 内置件由 {@link CmpSchema} 映射（source=SYSTEM）；自定义件解析 databus_component.param_schema
 * 同构 JSON 得到（source=CUSTOM）。新建独立 VO 而非复用 DatabusComponentVo，避免两套口径混用。
 *
 * @param code        物料注册名
 * @param name        物料名
 * @param shortName   网格短名
 * @param group       物料分组
 * @param icon        Iconify 图标名
 * @param color       面板色值
 * @param description 一句话描述
 * @param nodeType    LiteFlow 节点类型
 * @param editor      配置区编辑器形态（form/script）
 * @param source      物料来源（SYSTEM/CUSTOM）
 * @param sort        排序
 * @param dataExample 配置 JSON 示例（仅前端 JSON 高级模式占位提示，可空）
 * @param schema      配置字段 schema 体
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentOptionVo(String code,
                                String name,
                                String shortName,
                                String group,
                                String icon,
                                String color,
                                String description,
                                NodeTypeKind nodeType,
                                EditorKind editor,
                                ComponentSource source,
                                Integer sort,
                                String dataExample,
                                ComponentSchemaBody schema) {

    /**
     * 内置件：由注册中心的不可变 CmpSchema 映射。
     */
    public static ComponentOptionVo ofSystem(CmpSchema cmp) {
        return new ComponentOptionVo(
            cmp.code(),
            cmp.name(),
            cmp.shortName(),
            cmp.group(),
            cmp.icon(),
            cmp.color(),
            cmp.description(),
            cmp.nodeType(),
            cmp.editor(),
            ComponentSource.SYSTEM,
            cmp.sort(),
            cmp.dataExample(),
            new ComponentSchemaBody(cmp.fields())
        );
    }

    /**
     * 自定义行：以 param_schema 同构 JSON 解析结果为底，用表列覆盖身份字段
     * （component_code/component_name/icon/description 是 CRUD 正本）。
     *
     * @param parsed  param_schema 解析出的选项（可为空壳）
     * @param fallbackCode 表列 component_code（必填，身份正本）
     * @param fallbackName 表列 component_name（JSON 缺 name 时兜底）
     * @param fallbackIcon 表列 icon（JSON 缺 icon 时兜底）
     * @param fallbackDescription 表列 description（JSON 缺 description 时兜底）
     */
    public static ComponentOptionVo ofCustom(ComponentOptionVo parsed,
                                             String fallbackCode,
                                             String fallbackName,
                                             String fallbackIcon,
                                             String fallbackDescription) {
        return new ComponentOptionVo(
            fallbackCode,
            parsed != null && parsed.name() != null ? parsed.name() : fallbackName,
            parsed != null ? parsed.shortName() : null,
            parsed != null ? parsed.group() : null,
            parsed != null && parsed.icon() != null ? parsed.icon() : fallbackIcon,
            parsed != null ? parsed.color() : null,
            parsed != null && parsed.description() != null ? parsed.description() : fallbackDescription,
            parsed != null ? parsed.nodeType() : null,
            parsed != null ? parsed.editor() : null,
            ComponentSource.CUSTOM,
            parsed != null ? parsed.sort() : null,
            parsed != null ? parsed.dataExample() : null,
            parsed != null ? parsed.schema() : null
        );
    }
}
