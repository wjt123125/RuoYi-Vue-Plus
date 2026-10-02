package org.dromara.databus.component.schema.annotation;

import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据总线物料配置字段声明（字段级，打在 Cfg 类字段上）。
 * <p>
 * 不打本注解的字段仍按 Java 类型推断兜底渲染（见 databus-schema-driven-form.md §3.2/§3.4）。
 *
 * @author databus
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DatabusProp {

    /**
     * 展示名；缺省用字段名。
     */
    String label() default "";

    /**
     * 说明文案（表单项下方小灰字）；Javadoc 不进字节码，运行期不可读，不依赖注释。
     */
    String description() default "";

    /**
     * 输入控件；缺省按 Java 类型推断（反射阶段解析为最终控件，输出不出现 AUTO）。
     */
    WidgetKind widget() default WidgetKind.AUTO;

    /**
     * 是否必填（仅前端表单模式拦截 + 管理台账展示，不加服务端校验）。
     */
    boolean required() default false;

    /**
     * select/multiselect 的固定候选，常量枚举场景。
     */
    Option[] options() default {};

    /**
     * 占位提示。
     */
    String placeholder() default "";

    /**
     * 字段表达式角色：DATA 要数据（{{ }} 求值）/ TARGET 起名字（裸路径）/ LITERAL 普通字面量（缺省）。
     */
    ExprRole exprRole() default ExprRole.LITERAL;

    /**
     * 条件显示：多条为 AND；field 支持点路径（相对当前对象层级）。
     */
    ShowWhen[] showWhen() default {};

    /**
     * 仅 KEY_VALUE_MAP 使用：Map 值的表达式角色（如 dataPatch.patch、httpRequest.headers
     * 的叶子值可以是 {@code {{ $.路径 }}} 表达式，标 DATA）。键恒为文本，无 keyExprRole。
     */
    ExprRole valueExprRole() default ExprRole.LITERAL;

    /**
     * 排序，缺省按字段声明顺序（HotSpot 下 getDeclaredFields 稳定为声明序）。
     */
    int order() default Integer.MAX_VALUE;

    /**
     * select/multiselect 候选项。
     */
    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @interface Option {

        /** 展示文案。 */
        String label();

        /** 提交值。 */
        String value();
    }

    /**
     * 字段条件显示 DSL：每条只声明一个非空操作符（eq/ne/in/notIn），同一字段上声明多条时为 AND。
     */
    @Target({})
    @Retention(RetentionPolicy.RUNTIME)
    @interface ShowWhen {

        /** 依据字段名（相对当前对象层级，支持点路径）。 */
        String field();

        /** 相等条件，空串表示不启用。 */
        String eq() default "";

        /** 不等条件，空串表示不启用。 */
        String ne() default "";

        /** 属于集合条件。 */
        String[] in() default {};

        /** 不属于集合条件。 */
        String[] notIn() default {};
    }
}
