package org.dromara.databus.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 物料业务域字典项。
 *
 * <p>字段名与实体列名不同（key ← domain_key、label ← domain_name、isDefault ← is_default 的 Y/N），
 * 故不走 {@code BaseMapperPlus.selectVoList}（其底层是 mapstruct-plus 按属性名映射），
 * 由服务层手动装配。component 名用 {@code isDefault}（不是 default，Java 关键字），
 * Jackson 对 record 按 component name 序列化，JSON key 即 isDefault。
 *
 * @param key       业务域key（与 databus_component.domain 对齐）
 * @param label     业务域中文名（台账树第二层目录标题）
 * @param color     目录色值
 * @param sort      显示顺序
 * @param isDefault 是否兜底域（domain 为空的件归入此域；全表至多一行为 true）
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentDomainVo(String key, String label, String color, Integer sort, Boolean isDefault) {
}
