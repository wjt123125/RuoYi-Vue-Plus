/**
 * 数据总线物料 schema 驱动配置：注解元模型 + 纯函数反射 + 启动扫描注册。
 * <p>
 * 分组：
 * <ul>
 *   <li>{@code annotation} —— {@code @DatabusCmp}（组件类）、{@code @DatabusProp}（Cfg 字段）；</li>
 *   <li>{@code enums} —— WidgetKind / ExprRole / NodeTypeKind / EditorKind；</li>
 *   <li>{@code model} —— 对 /options 输出的不可变 schema 记录；</li>
 *   <li>{@code introspect} —— CfgIntrospector，无 Spring 纯函数，Java 件与第二步 javax-pro 脚本件共用；</li>
 *   <li>{@code registry} / {@code scanner} —— 内存注册中心与容器启动后的一次性扫描。</li>
 * </ul>
 * 设计依据：docs/wiki/databus-schema-driven-form.md §3、§4。
 *
 * @author databus
 */
package org.dromara.databus.component.schema;
