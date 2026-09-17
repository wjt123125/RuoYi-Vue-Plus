package org.dromara.databus.component.data;

import lombok.Data;
import org.dromara.common.core.exception.ServiceException;

import java.util.List;

/**
 * 字段映射组件（fieldMap）的节点参数。
 * <pre>
 * { "mappings": [ { "from": "$.httpRequest1.response.data.captchaEnabled",
 *                   "to":   "$.fieldMap1.captchaEnabled",
 *                   "type": "boolean" } ] }
 * </pre>
 *
 * <p>支持两种搬运语义：
 * <ul>
 *   <li>单值搬运（默认，向后兼容）：from / to 均不含 {@code [*]}，从 from 读单值原值写入 to。
 *       <b>不配 type 时行为完全等同改造前</b>（原值搬运）。</li>
 *   <li>数组批量搬运（A3 新增）：from / to 均含 {@code [*]}（如 {@code $.orders[*].NAME}
 *       → {@code $.target.items[*].name}），逐元素读取、按索引写入目标数组的对应位置，
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
     * 字段搬运条目列表：from 源路径 → to 目标路径（均为纯路径，原值搬运，不做拼接）。
     */
    private List<Mapping> mappings;

    @Data
    public static class Mapping {

        /** 源 JSONPath。支持 {@code [*]} 通配符（与 to 同时含 [*] 时进入数组批量搬运）。 */
        private String from;

        /** 目标 JSONPath。支持 {@code [*]} 通配符（与 from 同时含 [*] 时进入数组批量搬运）。 */
        private String to;

        /**
         * 目标类型转换（可选）。
         * <p>取值 {@code int / string / boolean / double}，空则原值搬运。
         * 配置后会对每个搬运值（含批量分支的每个元素）调用 convertType 转换，
         * 失败抛 ServiceException。
         */
        private String type;
    }
}
