package org.dromara.databus.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 物料面板分组字典项。
 *
 * <p>字段名与实体列名不同（key ← group_key、label ← group_name），故不走
 * {@code BaseMapperPlus.selectVoList}（其底层是 mapstruct-plus 按属性名映射），
 * 由服务层手动装配。
 *
 * @param key   分组key（与 @DatabusCmp.group() 及 databus_component.group_name 对齐）
 * @param label 分组中文标题
 * @param color 分组色值（面板圆点/树目录色点）
 * @param sort  显示顺序
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentGroupVo(String key, String label, String color, Integer sort) {
}
