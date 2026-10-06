package org.dromara.databus.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.model.CmpSchema;
import org.dromara.databus.domain.DatabusComponent;

import java.util.List;

/**
 * 物料合流选项（/options 单项，schema JSON 契约 §5.1）。
 * <p>
 * 内置件由 {@link CmpSchema} 映射（source=SYSTEM）；自定义件由 databus_component
 * 表列装配（source=CUSTOM）：治理字段（name/icon/group/color/tags 等）直接取列，
 * schema 体解析 param_schema 列（契约缓存）。
 *
 * @param code          物料注册名
 * @param name          物料名
 * @param shortName     网格短名
 * @param group         物料分组
 * @param icon          Iconify 图标名
 * @param color         面板色值
 * @param description   一句话描述
 * @param nodeType      LiteFlow 节点类型
 * @param editor        配置区编辑器形态（form/script）
 * @param source        物料来源（SYSTEM/CUSTOM）
 * @param sort          排序
 * @param dataExample   配置 JSON 示例（仅前端 JSON 高级模式占位提示，可空）
 * @param schema        配置字段 schema 体
 * @param tags          编目标签（仅自定义行有；内置件 null）
 * @param deprecated    是否废弃（内置件 null；废弃件老链路可见、面板置灰）
 * @param deprecateNote 废弃提示文案
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
                                ComponentSchemaBody schema,
                                List<String> tags,
                                Boolean deprecated,
                                String deprecateNote) {

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
            new ComponentSchemaBody(cmp.fields()),
            null,
            null,
            null
        );
    }

    /**
     * 自定义行：治理字段全部以表列为正本，契约字段（nodeType/editor/schema 体/dataExample）
     * 取自契约缓存列；param_schema 解析失败时 schema 为 null，前端回退 JSON 编辑器。
     *
     * @param row  databus_component 表行
     * @param body param_schema 解析出的 schema 体（可为 null）
     */
    public static ComponentOptionVo ofCustom(DatabusComponent row, ComponentSchemaBody body) {
        return new ComponentOptionVo(
            row.getComponentCode(),
            row.getComponentName(),
            row.getShortName(),
            row.getGroupName(),
            row.getIcon(),
            row.getColor(),
            row.getDescription(),
            parseNodeType(row.getNodeType()),
            parseEditor(row.getEditor()),
            ComponentSource.CUSTOM,
            row.getSort(),
            row.getDataExample(),
            body,
            row.getTags(),
            "1".equals(row.getDeprecated()),
            row.getDeprecateNote()
        );
    }

    /**
     * 治理覆盖行（DB 行与内置件同码、无 script_body）：治理字段以 DB 列为正本
     * （空值回退内置注解），nodeType/editor/schema/dataExample 等契约字段仍取内置注解，
     * source=OVERLAY。对应立法②：未迁移 jar 件可插同码 DB 行做停用/废弃/打标/改名。
     *
     * @param sys  内置注解件 schema
     * @param row  同码 DB 治理行
     */
    public static ComponentOptionVo ofOverlay(CmpSchema sys, DatabusComponent row) {
        return new ComponentOptionVo(
            row.getComponentCode(),
            row.getComponentName(),
            firstNonBlank(row.getShortName(), sys.shortName()),
            firstNonBlank(row.getGroupName(), sys.group()),
            firstNonBlank(row.getIcon(), sys.icon()),
            firstNonBlank(row.getColor(), sys.color()),
            firstNonBlank(row.getDescription(), sys.description()),
            sys.nodeType(),
            sys.editor(),
            ComponentSource.OVERLAY,
            row.getSort() == null ? sys.sort() : row.getSort(),
            sys.dataExample(),
            new ComponentSchemaBody(sys.fields()),
            row.getTags(),
            "1".equals(row.getDeprecated()),
            row.getDeprecateNote()
        );
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }

    /** 表列脏值（非合法枚举）静默降级为 null，前端展示「-」，不阻断合流 */
    private static NodeTypeKind parseNodeType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NodeTypeKind.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static EditorKind parseEditor(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EditorKind.fromJson(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
