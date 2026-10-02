package org.dromara.databus.component.schema.model;

/**
 * select/multiselect 候选项（schema JSON 契约 §5.1）。
 *
 * @param label 展示文案
 * @param value 提交值
 * @author databus
 */
public record OptionVo(String label, String value) {
}
