package org.dromara.databus.component.schema.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;

/**
 * 物料配置字段 schema（schema JSON 契约 §5.1 字段对象）。
 * <p>
 * 不可变值对象；{@code fields} 非空时为嵌套对象分组（widget=OBJECT）或对象数组行编辑
 * （widget=OBJECT_ROWS）；{@code keyWidget/valueWidget/valueExprRole} 仅 KEY_VALUE_MAP 使用。
 *
 * @param name          字段名
 * @param label         展示名
 * @param description   说明文案
 * @param widget        最终控件（反射阶段已解析，不出现 AUTO）
 * @param required      是否必填（仅前端拦截 + 台账展示）
 * @param exprRole      表达式角色
 * @param placeholder   占位提示
 * @param order         排序；0 表示未显式声明（顺序以 fields 数组为准）
 * @param options       select/multiselect 候选
 * @param showWhen      条件显示规则（AND）
 * @param fields        嵌套子字段（OBJECT 分组 / OBJECT_ROWS 元素字段）
 * @param keyWidget     KEY_VALUE_MAP 键控件
 * @param valueWidget   KEY_VALUE_MAP 值控件
 * @param valueExprRole KEY_VALUE_MAP 值的表达式角色
 * @author databus
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PropSchema(String name,
                         String label,
                         String description,
                         WidgetKind widget,
                         boolean required,
                         ExprRole exprRole,
                         String placeholder,
                         int order,
                         List<OptionVo> options,
                         List<ShowWhenVo> showWhen,
                         List<PropSchema> fields,
                         WidgetKind keyWidget,
                         WidgetKind valueWidget,
                         ExprRole valueExprRole) {

    public PropSchema {
        options = options == null ? null : List.copyOf(options);
        showWhen = showWhen == null ? null : List.copyOf(showWhen);
        fields = fields == null ? null : List.copyOf(fields);
    }

    public static Builder builder(String name, WidgetKind widget) {
        return new Builder(name, widget);
    }

    /**
     * 反射器/注册器组装 PropSchema 的手工 builder（字段较多，避免长参数构造）。
     */
    public static final class Builder {

        private final String name;
        private final WidgetKind widget;
        private String label;
        private String description;
        private boolean required;
        private ExprRole exprRole;
        private String placeholder;
        private int order;
        private List<OptionVo> options;
        private List<ShowWhenVo> showWhen;
        private List<PropSchema> fields;
        private WidgetKind keyWidget;
        private WidgetKind valueWidget;
        private ExprRole valueExprRole;

        private Builder(String name, WidgetKind widget) {
            this.name = name;
            this.widget = widget;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder exprRole(ExprRole exprRole) {
            this.exprRole = exprRole;
            return this;
        }

        public Builder placeholder(String placeholder) {
            this.placeholder = placeholder;
            return this;
        }

        public Builder order(int order) {
            this.order = order;
            return this;
        }

        public Builder options(List<OptionVo> options) {
            this.options = options;
            return this;
        }

        public Builder showWhen(List<ShowWhenVo> showWhen) {
            this.showWhen = showWhen;
            return this;
        }

        public Builder fields(List<PropSchema> fields) {
            this.fields = fields;
            return this;
        }

        public Builder keyWidget(WidgetKind keyWidget) {
            this.keyWidget = keyWidget;
            return this;
        }

        public Builder valueWidget(WidgetKind valueWidget) {
            this.valueWidget = valueWidget;
            return this;
        }

        public Builder valueExprRole(ExprRole valueExprRole) {
            this.valueExprRole = valueExprRole;
            return this;
        }

        public PropSchema build() {
            return new PropSchema(name, label, description, widget, required, exprRole,
                placeholder, order, options, showWhen, fields, keyWidget, valueWidget, valueExprRole);
        }
    }
}
