package org.dromara.databus.component.schema.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.component.schema.enums.NodeTypeKind;

import java.util.List;

/**
 * 物料 schema（注册中心存储单元，schema JSON 契约 §5.1 顶层对象）。
 * <p>
 * source（SYSTEM/CUSTOM）是 /options 合流层概念，不在本模型内；脚本两物料 editor=SCRIPT，
 * 出现在 /options 与台账中，但配置区不走 SchemaForm。
 *
 * @param code        物料注册名
 * @param name        物料名
 * @param shortName   网格短名
 * @param group       物料分组
 * @param icon        Iconify 图标名
 * @param color       面板色值
 * @param description 一句话描述
 * @param nodeType    LiteFlow 节点类型
 * @param editor      配置区编辑器形态
 * @param sort        排序
 * @param dataExample 配置 JSON 示例（仅前端 JSON 高级模式占位提示，可空）
 * @param fields      配置字段 schema（SCRIPT 物料也反射其 Cfg，仅供台账展示）
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CmpSchema(String code,
                        String name,
                        String shortName,
                        String group,
                        String icon,
                        String color,
                        String description,
                        NodeTypeKind nodeType,
                        EditorKind editor,
                        int sort,
                        String dataExample,
                        List<PropSchema> fields) {

    public CmpSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
