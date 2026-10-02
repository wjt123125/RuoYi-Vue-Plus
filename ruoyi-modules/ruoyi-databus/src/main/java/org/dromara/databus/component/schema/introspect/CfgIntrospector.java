package org.dromara.databus.component.schema.introspect;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;
import org.dromara.databus.component.schema.model.OptionVo;
import org.dromara.databus.component.schema.model.PropSchema;
import org.dromara.databus.component.schema.model.ShowWhenVo;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cfg 配置类反射器：读取字段上的 {@link DatabusProp} 注解并按 Java 类型推断兜底，
 * 产出不可变 {@link PropSchema} 列表（databus-schema-driven-form.md §3.4 / §4.2）。
 * <p>
 * 纯函数、无 Spring 依赖：Java 件扫描器与第二步 javax-pro 脚本件（保存期只编译不实例化、
 * 脚本 ClassLoader 内反射注解）共用同一套反射规则。解析失败一律兜底 {@link WidgetKind#JSON}，
 * 不抛异常（表单不崩）；循环引用的嵌套 Bean 同样逃逸为 JSON。
 *
 * @author databus
 */
public final class CfgIntrospector {

    private CfgIntrospector() {
    }

    /**
     * 反射一个 Cfg 类的全部可配置字段（已按 order/声明序排序）。
     *
     * @param cfgClass 配置类（Java 件为 {@code @DatabusCmp.cfg()}，脚本件为其内嵌 Cfg）
     * @return 字段 schema 列表（永不返回 null）
     */
    public static List<PropSchema> introspect(Class<?> cfgClass) {
        return introspectFields(cfgClass, new HashSet<>());
    }

    // ---------------------------------------------------------------------
    // 核心递归
    // ---------------------------------------------------------------------

    private static List<PropSchema> introspectFields(Class<?> beanType, Set<Class<?>> guard) {
        Field[] declared = beanType.getDeclaredFields();
        List<IndexedField> indexed = new ArrayList<>(declared.length);
        for (int i = 0; i < declared.length; i++) {
            Field field = declared[i];
            if (isSkipped(field)) {
                continue;
            }
            DatabusProp ann = field.getAnnotation(DatabusProp.class);
            indexed.add(new IndexedField(buildProp(field, ann, guard), i,
                ann == null ? Integer.MAX_VALUE : ann.order()));
        }
        // 显式 order 的字段在前且按 order 升序；其余保持声明序
        indexed.sort((a, b) -> {
            int ga = a.explicitOrder() == Integer.MAX_VALUE ? 1 : 0;
            int gb = b.explicitOrder() == Integer.MAX_VALUE ? 1 : 0;
            if (ga != gb) {
                return ga - gb;
            }
            if (ga == 0 && a.explicitOrder() != b.explicitOrder()) {
                return Integer.compare(a.explicitOrder(), b.explicitOrder());
            }
            return Integer.compare(a.declaredIndex(), b.declaredIndex());
        });
        List<PropSchema> result = new ArrayList<>(indexed.size());
        for (IndexedField item : indexed) {
            result.add(item.prop());
        }
        return List.copyOf(result);
    }

    private static PropSchema buildProp(Field field, DatabusProp ann, Set<Class<?>> guard) {
        String name = field.getName();
        String label = ann != null && !ann.label().isBlank() ? ann.label() : name;
        String description = ann == null ? null : emptyToNull(ann.description());
        String placeholder = ann == null ? null : emptyToNull(ann.placeholder());
        boolean required = ann != null && ann.required();
        ExprRole exprRole = ann == null ? ExprRole.LITERAL : ann.exprRole();
        int outputOrder = ann != null && ann.order() != Integer.MAX_VALUE ? ann.order() : 0;

        FieldShape shape = resolveShape(field.getGenericType(), guard);
        WidgetKind widget = ann != null && ann.widget() != WidgetKind.AUTO
            ? ann.widget() : shape.widget();

        // 候选：显式注解优先；缺省时枚举（含集合/Map 值枚举）自动产出
        List<OptionVo> options = explicitOptions(ann);
        if (options == null && shape.enumType() != null
            && (widget == WidgetKind.SELECT || widget == WidgetKind.MULTISELECT)) {
            options = enumOptions(shape.enumType());
        }

        return PropSchema.builder(name, widget)
            .label(label)
            .description(description)
            .placeholder(placeholder)
            .required(required)
            .exprRole(exprRole)
            .order(outputOrder)
            .options(options)
            .showWhen(showWhenRules(ann))
            .fields(shape.nested())
            .keyWidget(shape.keyWidget())
            .valueWidget(shape.valueWidget())
            .valueExprRole(ann == null ? null : ann.valueExprRole())
            .build();
    }

    // ---------------------------------------------------------------------
    // 类型推断
    // ---------------------------------------------------------------------

    private record FieldShape(WidgetKind widget,
                              List<PropSchema> nested,
                              WidgetKind keyWidget,
                              WidgetKind valueWidget,
                              Class<? extends Enum<?>> enumType) {
        static FieldShape of(WidgetKind widget) {
            return new FieldShape(widget, null, null, null, null);
        }
    }

    private static FieldShape resolveShape(Type type, Set<Class<?>> guard) {
        Class<?> raw = erase(type);

        if (raw.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum<?>> enumType = (Class) raw;
            return new FieldShape(WidgetKind.SELECT, null, null, null, enumType);
        }
        if (CharSequence.class.isAssignableFrom(raw) || raw == char.class || raw == Character.class) {
            return FieldShape.of(WidgetKind.TEXT);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return FieldShape.of(WidgetKind.BOOLEAN);
        }
        if (isNumber(raw)) {
            return FieldShape.of(WidgetKind.NUMBER);
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type valueType = type instanceof ParameterizedType pt ? pt.getActualTypeArguments()[1] : Object.class;
            return new FieldShape(WidgetKind.KEY_VALUE_MAP, null, WidgetKind.TEXT, scalarWidget(valueType), null);
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Type elementType = type instanceof ParameterizedType pt
                ? pt.getActualTypeArguments()[0] : Object.class;
            return collectionShape(elementType, guard);
        }
        if (raw.isArray()) {
            return collectionShape(raw.getComponentType(), guard);
        }
        if (isBean(raw)) {
            List<PropSchema> nested = safeNestedFields(raw, guard);
            if (nested == null) {
                // 循环引用逃逸
                return FieldShape.of(WidgetKind.JSON);
            }
            return new FieldShape(WidgetKind.OBJECT, nested, null, null, null);
        }
        // Object / java.time / 解析不出的泛型变量：JSON 逃生舱
        return FieldShape.of(WidgetKind.JSON);
    }

    private static FieldShape collectionShape(Type elementType, Set<Class<?>> guard) {
        Class<?> element = erase(elementType);
        if (CharSequence.class.isAssignableFrom(element)) {
            return FieldShape.of(WidgetKind.MULTISELECT);
        }
        if (element.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum<?>> enumType = (Class) element;
            return new FieldShape(WidgetKind.MULTISELECT, null, null, null, enumType);
        }
        if (isBean(element)) {
            List<PropSchema> nested = safeNestedFields(element, guard);
            if (nested == null) {
                return FieldShape.of(WidgetKind.JSON);
            }
            return new FieldShape(WidgetKind.OBJECT_ROWS, nested, null, null, null);
        }
        // List<Number/Boolean/Map/Object> 等一律 JSON 逃生舱（自由标签串只服务字符串）
        return FieldShape.of(WidgetKind.JSON);
    }

    /** Map 值控件推断：只处理标量，复杂值走 JSON 逃生舱。 */
    private static WidgetKind scalarWidget(Type valueType) {
        Class<?> value = erase(valueType);
        if (CharSequence.class.isAssignableFrom(value)) {
            return WidgetKind.TEXT;
        }
        if (value == boolean.class || value == Boolean.class) {
            return WidgetKind.BOOLEAN;
        }
        if (isNumber(value)) {
            return WidgetKind.NUMBER;
        }
        if (value.isEnum()) {
            return WidgetKind.SELECT;
        }
        return WidgetKind.JSON;
    }

    /**
     * 递归嵌套 Bean 字段；循环引用返回 null（调用方逃逸为 JSON）。
     */
    private static List<PropSchema> safeNestedFields(Class<?> beanType, Set<Class<?>> guard) {
        if (guard.contains(beanType)) {
            return null;
        }
        Set<Class<?>> next = new HashSet<>(guard);
        next.add(beanType);
        return introspectFields(beanType, next);
    }

    // ---------------------------------------------------------------------
    // 类型工具
    // ---------------------------------------------------------------------

    private static boolean isNumber(Class<?> type) {
        return type.isPrimitive()
            ? type == int.class || type == long.class || type == double.class
                || type == short.class || type == float.class
            : Number.class.isAssignableFrom(type);
    }

    /**
     * 嵌套 Bean：非 JDK 类型（java.* 视为值类型）、非枚举/集合/Map/数组/原始类型。
     */
    private static boolean isBean(Class<?> type) {
        return type != Object.class
            && !type.isPrimitive()
            && !type.isArray()
            && !type.isEnum()
            && !Collection.class.isAssignableFrom(type)
            && !Map.class.isAssignableFrom(type)
            && !type.getName().startsWith("java.")
            && !type.getName().startsWith("javax.");
    }

    private static Class<?> erase(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        if (type instanceof GenericArrayType gat) {
            Class<?> component = erase(gat.getGenericComponentType());
            return java.lang.reflect.Array.newInstance(component, 0).getClass();
        }
        if (type instanceof WildcardType wt) {
            return erase(wt.getUpperBounds()[0]);
        }
        // TypeVariable 等解析不出
        return Object.class;
    }

    // ---------------------------------------------------------------------
    // 注解映射 / 字段过滤
    // ---------------------------------------------------------------------

    private static boolean isSkipped(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers) || field.isSynthetic()) {
            return true;
        }
        JsonIgnore jsonIgnore = field.getAnnotation(JsonIgnore.class);
        return jsonIgnore != null && jsonIgnore.value();
    }

    private static List<OptionVo> explicitOptions(DatabusProp ann) {
        if (ann == null || ann.options().length == 0) {
            return null;
        }
        List<OptionVo> result = new ArrayList<>(ann.options().length);
        for (DatabusProp.Option option : ann.options()) {
            result.add(new OptionVo(option.label(), option.value()));
        }
        return List.copyOf(result);
    }

    private static List<OptionVo> enumOptions(Class<? extends Enum<?>> enumType) {
        Enum<?>[] constants = enumType.getEnumConstants();
        List<OptionVo> result = new ArrayList<>(constants.length);
        for (Enum<?> constant : constants) {
            result.add(new OptionVo(constant.name(), constant.name()));
        }
        return List.copyOf(result);
    }

    private static List<ShowWhenVo> showWhenRules(DatabusProp ann) {
        if (ann == null || ann.showWhen().length == 0) {
            return null;
        }
        List<ShowWhenVo> result = new ArrayList<>(ann.showWhen().length);
        for (DatabusProp.ShowWhen rule : ann.showWhen()) {
            result.add(new ShowWhenVo(
                rule.field(),
                emptyToNull(rule.eq()),
                emptyToNull(rule.ne()),
                rule.in().length == 0 ? null : List.of(rule.in()),
                rule.notIn().length == 0 ? null : List.of(rule.notIn())
            ));
        }
        return List.copyOf(result);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private record IndexedField(PropSchema prop, int declaredIndex, int explicitOrder) {
    }
}
