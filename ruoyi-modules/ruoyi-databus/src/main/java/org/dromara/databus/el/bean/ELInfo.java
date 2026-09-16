package org.dromara.databus.el.bean;

import lombok.Data;

/**
 * EL 表达式信息载体（ELReqVO）。
 * <p>
 * 双向转换的"输入/输出信封"：
 * <ul>
 *   <li><b>EL → JSON 方向</b>：前端传入 chainId + elStr，由
 *       {@code ExpressGenerator.generateJsonEL} 解析成画布 JSON 树（CmpProperty）。</li>
 *   <li><b>JSON → EL 方向</b>：由 {@code ExpressGenerator.generateEL} 把画布 JSON 树
 *       拼回 EL 字符串，装进本对象的 elStr 返回给前端。</li>
 * </ul>
 * 示例：chainId = "myChain"，elStr = "THEN(a, b, c);"
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Data
public class ELInfo {

    /**
     * 编排链 ID。
     * EL → JSON 时必传：LiteFlow 解析 EL 时需要知道当前链的 ID，
     * 表达式里可用 {@code chainId} 关键字引用自身（自递归等场景）。
     */
    private String chainId;

    /**
     * LiteFlow EL 表达式文本，如：THEN(a, b, c).id("dog");
     */
    private String elStr;

}
