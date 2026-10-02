package org.dromara.databus.component.schema.scanner;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.component.schema.enums.NodeTypeKind;
import org.dromara.databus.component.schema.introspect.CfgIntrospector;
import org.dromara.databus.component.schema.model.CmpSchema;
import org.dromara.databus.component.schema.registry.DefaultComponentSchemaRegistry;
import org.dromara.databus.component.cfg.ScriptCfg;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 物料 schema 启动扫描器（databus-schema-driven-form.md §4.1）。
 * <p>
 * 容器就绪后扫一遍全部 {@link DatabusNodeComponent} bean：有 {@link DatabusCmp} 的反射其 Cfg
 * 生成 {@link CmpSchema}，未注解的件只登记日志（前端继续走 JSON 编辑器，非故障）；
 * 再追加脚本两物料（script/booleanScript，非 Spring bean，常量补充清单，§3.5）。
 * code 冲突 / Java 件未声明 cfg 直接 fail-fast。
 *
 * @author databus
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComponentSchemaScanner implements ApplicationRunner {

    private final ApplicationContext applicationContext;
    private final DefaultComponentSchemaRegistry registry;

    @Override
    public void run(ApplicationArguments args) {
        Map<String, CmpSchema> snapshot = new LinkedHashMap<>();
        Map<String, String> codeOwners = new LinkedHashMap<>();
        Map<String, String> unannotated = new LinkedHashMap<>();

        applicationContext.getBeansOfType(DatabusNodeComponent.class).forEach((beanName, bean) -> {
            Class<?> beanClass = ClassUtils.getUserClass(bean);
            DatabusCmp cmp = AnnotationUtils.findAnnotation(beanClass, DatabusCmp.class);
            if (cmp == null) {
                unannotated.put(beanName, beanClass.getName());
                return;
            }
            String code = resolveCode(cmp, beanClass, beanName);
            if (cmp.cfg() == void.class) {
                throw new IllegalStateException(
                    "@DatabusCmp(cfg) 必填缺失：" + beanClass.getName() + "（Java 件必须显式声明配置类）");
            }
            CmpSchema schema = new CmpSchema(
                code,
                cmp.name(),
                cmp.shortName(),
                cmp.group(),
                cmp.icon(),
                cmp.color(),
                cmp.description(),
                cmp.nodeType(),
                EditorKind.FORM,
                cmp.sort(),
                CfgIntrospector.introspect(cmp.cfg())
            );
            CmpSchema previous = snapshot.put(code, schema);
            if (previous != null) {
                throw new IllegalStateException("物料 code 冲突：\"" + code + "\" 同时被 "
                    + codeOwners.get(code) + " 与 " + beanClass.getName() + " 注册");
            }
            codeOwners.put(code, beanClass.getName());
        });

        registerSupplement(snapshot, codeOwners,
            "script", "脚本", "脚本", "ph:code", "#9c27b0",
            "Groovy 等脚本语言编写的任意代码，可读写数据空间", NodeTypeKind.NODE);
        registerSupplement(snapshot, codeOwners,
            "booleanScript", "条件脚本", "条件脚本", "ph:terminal-window", "#e6a23c",
            "返回 true/false 的脚本节点，可放入 IF/WHILE 条件槽", NodeTypeKind.BOOLEAN);

        registry.replaceAll(snapshot);

        log.info("[databus-schema] 已注册物料 schema {} 个（含脚本补充件），未注解件 {} 个（继续走 JSON 编辑器）",
            snapshot.size(), unannotated.size());
        snapshot.values().forEach(schema -> log.info("[databus-schema] 注册成功 code={}, name={}, editor={}, fields={}",
            schema.code(), schema.name(), schema.editor().jsonValue(), schema.fields().size()));
        unannotated.forEach((beanName, className) ->
            log.info("[databus-schema] 未注解跳过 bean={}, class={}", beanName, className));
    }

    /**
     * 注册名：{@code @DatabusCmp.code} 显式优先；缺省取同类 {@code @LiteflowComponent} 的 value；
     * 都拿不到则 fail-fast（不静默用 beanName 兜底，避免与 LiteFlow 注册名漂移）。
     */
    private String resolveCode(DatabusCmp cmp, Class<?> beanClass, String beanName) {
        if (!cmp.code().isBlank()) {
            return cmp.code();
        }
        LiteflowComponent liteflowComponent = AnnotationUtils.findAnnotation(beanClass, LiteflowComponent.class);
        if (liteflowComponent != null && !liteflowComponent.value().isBlank()) {
            return liteflowComponent.value();
        }
        throw new IllegalStateException(
            "@DatabusCmp 缺少 code 且类上无有效 @LiteflowComponent value：" + beanClass.getName()
                + "（beanName=" + beanName + "）");
    }

    /**
     * 脚本两物料补充注册（非 Spring bean，扫不到）；展示元信息与 cmp-defs.ts 逐项一致。
     */
    private void registerSupplement(Map<String, CmpSchema> snapshot, Map<String, String> codeOwners,
                                    String code, String name, String shortName, String icon, String color,
                                    String description, NodeTypeKind nodeType) {
        if (snapshot.containsKey(code)) {
            throw new IllegalStateException("物料 code 冲突：脚本补充件 \"" + code
                + "\" 已被 Java 件 " + codeOwners.get(code) + " 占用");
        }
        snapshot.put(code, new CmpSchema(
            code, name, shortName, "business", icon, color, description,
            nodeType, EditorKind.SCRIPT, 100,
            CfgIntrospector.introspect(ScriptCfg.class)));
        codeOwners.put(code, "script-supplement:" + code);
    }
}
