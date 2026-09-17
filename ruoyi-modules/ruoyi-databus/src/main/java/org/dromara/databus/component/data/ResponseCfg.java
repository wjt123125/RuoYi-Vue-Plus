package org.dromara.databus.component.data;

import lombok.Data;

/**
 * 响应组装组件（response）的节点参数。
 * <pre>
 * { "result": true, "msg": "$.httpRequest1.response.msg", "dataPath": "$.fieldMap1" }
 * </pre>
 *
 * @author databus
 */
@Data
public class ResponseCfg {

    /**
     * 执行结果：布尔字面量，或解析后为布尔/布尔字符串的路径。
     */
    private Object result;

    /**
     * 提示信息：字面量或裸路径 / 混合字符串。
     */
    private Object msg;

    /**
     * 返回数据所在的 JSONPath（纯路径整取）；不填则 data 为 null。
     */
    private String dataPath;
}
