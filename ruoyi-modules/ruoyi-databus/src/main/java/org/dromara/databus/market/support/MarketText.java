package org.dromara.databus.market.support;

/**
 * 市场模块共用的字符串小工具。
 * <p>
 * 这里只放两个以上类都要用的：{@code escapeLike} 查询那边搜关键字要用，
 * 安装那边查链有没有被引用、刷新计数也要用。
 * 其它字符串小工具（截断、取最后一段、空串转 null）只有安装那边用，
 * 就留在 MarketInstallService 里，不往这里搬，免得这个类什么都装、变成一堆不相干的东西。
 * <p>
 * databus 模块里以前已经有四份各自私有的、写法一样的 escapeLike（连接配置、市场、组件、链），
 * 拆类之后市场内部不能再抄出第五份第六份，所以在这里共用一份。
 *
 * @author databus
 * @since 2026-10-26
 */
public final class MarketText {

    private MarketText() {
    }

    /**
     * 给 MySQL LIKE 的通配符（% 和 _）前面加上反斜杠，免得用户搜的关键字被当成通配符
     * （反斜杠是 MySQL 默认的转义字符）。
     */
    public static String escapeLike(String value) {
        return value.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }
}
