package org.dromara.databus.component.schema.registry;

import org.dromara.databus.component.schema.model.CmpSchema;

import java.util.Collection;

/**
 * 物料 schema 注册中心（databus-schema-driven-form.md §4.3）。
 * <p>
 * 纯内存、无缓存 TTL：注解随 jar 版本走，重启即刷；不查库、不调网络。
 * 未打 {@code @DatabusCmp} 的件查不到，消费端回退现有 JSON 编辑器。
 *
 * @author databus
 */
public interface ComponentSchemaRegistry {

    /**
     * 按注册名取 schema。
     *
     * @param code 物料注册名（LiteFlow 组件 id）
     * @return schema；未注册返回 null
     */
    CmpSchema get(String code);

    /**
     * 全部内置 schema（含脚本补充件），按 sort 升序。
     */
    Collection<CmpSchema> all();
}
