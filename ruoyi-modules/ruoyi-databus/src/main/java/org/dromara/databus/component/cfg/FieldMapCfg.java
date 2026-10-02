package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

import java.util.List;

/**
 * 字段映射组件（fieldMap）的节点参数。
 * <pre>
 * { "mappings": [ { "from": "{{ $.httpRequest1.response.data.captchaEnabled }}",
 *                   "to":   "$.fieldMap1.captchaEnabled",
 *                   "type": "boolean" } ] }
 * </pre>
 *
 * <p>支持两种搬运语义：
 * <ul>
 *   <li>单值搬运（默认）：from 表达式（{@code {{ $.路径 }}}）不含 {@code [*]}，从 from 求单值原值写入 to。
 *       <b>不配 type 时为原值搬运</b>。</li>
 *   <li>数组批量搬运：from / to 均含 {@code [*]}（如 {@code {{ $.orders[*].NAME }}}
 *       → {@code $.target.items[*].name}），from 求值得列表、逐元素按索引写入目标数组，
 *       每个元素仍按单值处理（可叠加 type 转换）。</li>
 * </ul>
 *
 * <p>{@code type} 可选，取值 {@code int / string / boolean / double}，空则原值搬运；
 * 配置后对每个搬运值（含批量分支的每个元素）做类型转换，失败抛 {@link ServiceException}。
 *
 * @author databus
 */
@Data
public class FieldMapCfg {

    /**
     * 字段搬运条目列表：from 源表达式（要数据，{@code {{ $.路径 }}}）→ to 目标位置名（起名字，裸路径）。
     */
    @DatabusProp(label = "字段映射列表", required = true, order = 1)
    private List<Mapping> mappings;

    @Data
    public static class Mapping {

        /** 源表达式（{@code {{ $.路径 }}}）。含 {@code [*]}（与 to 同时含）时进入数组批量搬运。 */
        @DatabusProp(
            label = "源表达式 from", required = true, exprRole = ExprRole.DATA, order = 1,
            description = "要数据：{{ $.路径 }}；与 to 同时含 [*] 时按数组逐元素搬运"
        )
        private String from;

        /** 目标位置名（裸路径）。含 {@code [*]}（与 from 同时含）时进入数组批量搬运。 */
        @DatabusProp(
            label = "目标路径 to", required = true, exprRole = ExprRole.TARGET, order = 2,
            placeholder = "$.数据空间.字段",
            description = "起名字：裸写入路径，不求值"
        )
        private String to;

        /**
         * 目标类型转换（可选）。
         * <p>取值 {@code int / string / boolean / double}，空则原值搬运。
         * 配置后会对每个搬运值（含批量分支的每个元素）调用 convertType 转换，
         * 失败抛 ServiceException。
         */
        @DatabusProp(
            label = "类型转换 type", widget = WidgetKind.SELECT, order = 3,
            description = "不选为原值搬运",
            options = {
                @DatabusProp.Option(label = "整数 int", value = "int"),
                @DatabusProp.Option(label = "字符串 string", value = "string"),
                @DatabusProp.Option(label = "布尔 boolean", value = "boolean"),
                @DatabusProp.Option(label = "浮点 double", value = "double")
            }
        )
        private String type;
    }
}
