package org.dromara.databus.component.data;

import lombok.Data;

import java.util.List;

/**
 * 字段映射组件（fieldMap）的节点参数。
 * <pre>
 * { "mappings": [ { "from": "$.httpRequest1.response.data.captchaEnabled",
 *                   "to":   "$.fieldMap1.captchaEnabled" } ] }
 * </pre>
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

        /** 源 JSONPath。 */
        private String from;

        /** 目标 JSONPath。 */
        private String to;
    }
}
