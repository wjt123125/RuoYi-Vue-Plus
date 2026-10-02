package org.dromara.databus.component.schema.annotation;

import org.dromara.databus.component.schema.enums.NodeTypeKind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据总线物料声明（类级，打在 LiteFlow 组件类上）。
 * <p>
 * 扫描器在容器启动后读取本注解，反射 {@link #cfg()} 配置类生成表单 schema
 * （见 databus-schema-driven-form.md §3.1）。物料展示元信息（name/icon/color/group）
 * 必须与前端 cmp-defs.ts 逐项一致（过渡期双事实源纪律）。
 *
 * @author databus
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DatabusCmp {

    /**
     * 注册名；缺省取同类上 {@code @LiteflowComponent} 的 value。
     */
    String code() default "";

    /**
     * 物料名（面板/台账展示），与 cmp-defs.ts label 同源。
     */
    String name();

    /**
     * 物料网格短名，可选。
     */
    String shortName() default "";

    /**
     * 物料分组：flow/sequence/branch/loop/other/subflow/business，业务件默认 business。
     */
    String group() default "business";

    /**
     * Iconify 图标名，如 ph:pencil-simple。
     */
    String icon();

    /**
     * 面板色值，如 #67c23a。
     */
    String color() default "#409eff";

    /**
     * 一句话描述（cmp-defs.ts desc 同源）。
     */
    String description();

    /**
     * LiteFlow 节点类型，默认普通件；布尔/循环/选择件显式声明。
     */
    NodeTypeKind nodeType() default NodeTypeKind.NODE;

    /**
     * 面板/台账排序，缺省 100。
     */
    int sort() default 100;

    /**
     * 配置类（反射字段的根），显式绑定，不做字节码猜测 getCmpData 泛型。
     * <p>
     * 形态①（Java 件）必填，缺省值 {@code void.class} 为哨兵，扫描器对 Java 件做必填校验；
     * 形态②（javax-pro 脚本件，第二步）固定寻找脚本类内嵌的同名 Cfg，允许缺省。
     */
    Class<?> cfg() default void.class;
}
