package org.dromara.databus.domain.vo;

import org.dromara.databus.component.schema.model.PropSchema;

import java.util.List;

/**
 * 物料 /options schema 体（schema JSON 契约 §5.1：fields 嵌在 schema 对象下）。
 *
 * @param fields 配置字段 schema
 * @author databus
 */
public record ComponentSchemaBody(List<PropSchema> fields) {

    public ComponentSchemaBody {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
