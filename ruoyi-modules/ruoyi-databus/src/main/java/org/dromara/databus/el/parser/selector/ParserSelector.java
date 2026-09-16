package org.dromara.databus.el.parser.selector;

import org.dromara.databus.el.parser.base.ExpressParser;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Condition;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.dromara.databus.el.parser.factory.ExpressParserFactory.PARSER_MAP;


/**
 * 解析器选择器 —— 按"类型"从 {@code ExpressParserFactory.PARSER_MAP} 找解析器。
 * <p>
 * 两个重载入口对应两种使用场景：
 * <ul>
 *   <li>{@link #getParser(Condition)}：EL→JSON 方向，手里是 LiteFlow 的
 *       Condition 对象，用它的 conditionType 匹配；</li>
 *   <li>{@link #getParser(String)}：JSON→EL 方向，手里是 CmpProperty.type
 *       （小写后的 EL 关键字，如 "then"、"switch"），直接按字符串匹配。</li>
 * </ul>
 * 匹配策略是 {@code contains}（模糊包含），而非全等：
 * PARSER_MAP 的 key 是 ConditionTypeEnum.getType() 的产物，部分类型名较长
 * （如 AND_OR_OPT 的类型名包含组合语义），contains 能同时兼容
 * "and_or_opt" 这类组合 key 与传入的简短 type。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/19
 */
public class ParserSelector {


    /**
     * JSON→EL：按 EL 关键字字符串（小写）找解析器，找不到抛运行时异常。
     */
    public static ExpressParser getParser(String type) {
        return foundMatchParserType(type).map(expressParserMapper).orElseThrow(RuntimeException::new);
    }

    /**
     * EL→JSON：按 Condition 自身的 conditionType 找解析器，找不到抛运行时异常。
     */
    public static <T extends Condition> ExpressParser getParser(T condition) {
        return foundMatchParserType(condition).map(expressParserMapper).orElseThrow(RuntimeException::new);
    }

    /**
     * 遍历注册表，返回第一个与 condition 的 conditionType contains 匹配的 key。
     */
    private static <T extends Condition> Optional<String> foundMatchParserType(T condition) {
        for (String key : PARSER_MAP.keySet()) {
            String parserType = condition.getConditionType().getType();
            if (keyMatcher.test(key, parserType)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * 遍历注册表，返回第一个与给定 type 字符串 contains 匹配的 key。
     */
    private static Optional<String> foundMatchParserType(String type) {
        for (String key : PARSER_MAP.keySet()) {
            if (keyMatcher.test(key, type)) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** key → 解析器实例 的安全取值函数（key 为 null 时返回 null） */
    private static final Function<String, ExpressParser> expressParserMapper = parserType -> Optional.ofNullable(parserType)
            .map(PARSER_MAP::get)
            .orElse(null);

    /** 模糊匹配器：key.contains(type) 或 type.contains(key) 语义由参数顺序决定 */
    private static final BiPredicate<String, String> keyMatcher = StringUtils::contains;

    /** 判断某 key 是否属于 AND/OR 组合类型（预留的归类判断，当前未被调用） */
    private static final Predicate<String> andOrOptMatcher =
            key -> StringUtils.contains(ConditionTypeEnum.TYPE_AND_OR_OPT.getType(), key);

}
