package org.dromara.databus.el.parser.base;

/**
 * 布尔类 Condition 的抽象基类（AND / OR / NOT 的标记性父类）。
 * <p>
 * 三者的 EL 形态简单（{@code AND(a,b)}、{@code OR(a,b)}、{@code NOT(a)}），
 * 没有可抽取的额外公共逻辑，所以目前是个空类，仅用于类型归类和统一扩展点。
 * <p>
 * 注意 AND/OR 共用一个 LiteFlow Condition 类型（TYPE_AND_OR_OPT），
 * 因此两个关键字<b>共用</b> {@link org.dromara.databus.el.parser.el.AndOrConditionParser}，
 * 由 getExpressType / generateELMethod 在运行时区分具体是 AND 还是 OR。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/24
 */
public abstract class AbstractAndOrNotExpressParser extends AbstractExpressParser {

}
