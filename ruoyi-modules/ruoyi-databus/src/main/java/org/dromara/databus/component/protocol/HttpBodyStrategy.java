package org.dromara.databus.component.protocol;

import org.springframework.http.MediaType;

/**
 * HTTP 请求体序列化策略（bodyType 一种媒介一个实现）。
 * <p>策略只负责「配置校验 + 产出可交给 RestClient 的 body 与 Content-Type」，
 * 不做路径解析（调用方先经 DatabusContext 递归解析）。
 *
 * @author databus
 */
interface HttpBodyStrategy {

    /**
     * 媒介标识：json / form / raw。
     */
    String type();

    /**
     * 校验并产出请求体。
     *
     * @param resolvedBody   路径解析后的 body 值
     * @param rawContentType raw 媒介手填的 Content-Type（其他媒介忽略）
     * @param tag            当前节点数据空间名（错误信息带 tag）
     * @return 可直接 {@code RestClient.body(...)} 的内容与 Content-Type
     */
    PreparedBody prepare(Object resolvedBody, String rawContentType, String tag);

    /**
     * 请求体准备结果。
     *
     * @param content     交给 RestClient 的内容（json/raw 为字符串，form 为 MultiValueMap）
     * @param contentType 该媒介固定/推断的 Content-Type
     */
    record PreparedBody(Object content, MediaType contentType) {
    }
}
