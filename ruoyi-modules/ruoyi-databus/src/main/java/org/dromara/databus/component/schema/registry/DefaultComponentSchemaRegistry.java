package org.dromara.databus.component.schema.registry;

import org.dromara.databus.component.schema.model.CmpSchema;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 ConcurrentHashMap 的 {@link ComponentSchemaRegistry} 默认实现。
 * <p>
 * 写操作只在启动期由 {@code ComponentSchemaScanner} 批量执行一次，运行期只读；
 * {@link #all()} 每次返回按 sort 排序的新快照，调用方遍历期间与注册写互不干扰。
 *
 * @author databus
 */
@Component
public class DefaultComponentSchemaRegistry implements ComponentSchemaRegistry {

    private final Map<String, CmpSchema> schemas = new ConcurrentHashMap<>();

    @Override
    public CmpSchema get(String code) {
        return code == null ? null : schemas.get(code);
    }

    @Override
    public List<CmpSchema> all() {
        return schemas.values().stream()
            .sorted(Comparator.comparingInt(CmpSchema::sort).thenComparing(CmpSchema::code))
            .toList();
    }

    /**
     * 启动期批量替换：重复 code 直接拒绝（列出冲突类来源由扫描器提前 fail-fast，
     * 此处做最后一道防线）。
     */
    public void replaceAll(Map<String, CmpSchema> snapshot) {
        snapshot.forEach((code, schema) -> {
            if (!code.equals(schema.code())) {
                throw new IllegalStateException("物料 schema 注册键与 code 不一致：" + code + " / " + schema.code());
            }
        });
        schemas.clear();
        schemas.putAll(snapshot);
    }
}
