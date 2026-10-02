package org.dromara.databus.component.schema.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 字段条件显示规则（schema JSON 契约 §5.1）：同一字段多条规则为 AND。
 *
 * @param field 依据字段名（相对当前对象层级，支持点路径）
 * @param eq    相等条件，null 表示不启用
 * @param ne    不等条件，null 表示不启用
 * @param in    属于集合，null 表示不启用
 * @param notIn 不属于集合，null 表示不启用
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShowWhenVo(String field,
                         String eq,
                         String ne,
                         List<String> in,
                         List<String> notIn) {

    public ShowWhenVo {
        if (in != null) {
            in = List.copyOf(in);
        }
        if (notIn != null) {
            notIn = List.copyOf(notIn);
        }
    }
}
