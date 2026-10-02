package org.dromara.databus.component.schema.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 物料配置区编辑器形态：通用表单 / 脚本专用编辑器（schema JSON 契约为小写形式）。
 *
 * @author databus
 */
public enum EditorKind {

    /** SchemaForm 可渲染。 */
    FORM,

    /** 脚本专用编辑器（script/booleanScript），SchemaForm 不渲染，schema 仅供台账展示。 */
    SCRIPT;

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @JsonCreator
    public static EditorKind fromJson(String value) {
        return value == null ? null : EditorKind.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
