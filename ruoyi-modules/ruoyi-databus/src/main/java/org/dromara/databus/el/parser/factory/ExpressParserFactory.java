package org.dromara.databus.el.parser.factory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.dromara.databus.el.parser.base.ExpressParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析器注册工厂 —— 启动时把所有 ExpressParser 实现收集进静态容器。
 * <p>
 * 工作机制：Spring 容器启动后，通过 {@code @Autowired(required=false)} 把
 * 所有标记了 {@code @Component} 的解析器实现（ThenConditionParser、
 * IfConditionParser……）注入进来，逐个注册到 PARSER_MAP：
 * <pre>
 * key   = parser.parserKey()  即 ConditionTypeEnum.getType()，如 "then"、"switch"
 *         （ChainParser 覆盖 parserKey() 返回 "chain"）
 * value = 解析器实例本身
 * </pre>
 * 之后 {@link org.dromara.databus.el.parser.selector.ParserSelector} 全部
 * 从这个静态 Map 查找解析器（不经过 Spring），实现"按 Condition 类型自动路由"。
 * <p>
 * 新增一种 EL 关键字时无需改这里：写一个新解析器 + @Component 即自动注册。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
@Slf4j
@Component
public class ExpressParserFactory {

    /**
     * 解析器容器：key = ConditionTypeEnum.getType()（小写类型名），
     * value = 对应解析器实例。静态的，供 ParserSelector 直接静态访问。
     */
    public static final Map<String, ExpressParser> PARSER_MAP = new ConcurrentHashMap<>();

    /**
     * Spring 注入点：收集容器内所有 ExpressParser 实现并注册。
     * required=false：一个解析器都没有也不报错（空容器可运行）。
     */
    @Autowired(required = false)
    public void setParsers(List<ExpressParser> parsers) {
        if (CollUtil.isNotEmpty(parsers)) {
            parsers.forEach(this::register);
        }
    }

    /**
     * 注册单个解析器；parserKey() 为空（未绑定注册 key）的直接忽略。
     * <p>
     * key 取 {@link ExpressParser#parserKey()}：常规解析器等于
     * ConditionTypeEnum.getType()（如 "then"）；ChainParser 这类
     * "非 Condition 类型"的解析器通过覆盖 parserKey() 自定义注册名，
     * 此时 parserType() 为 null（不再作为忽略条件）。
     */
    public void register(ExpressParser parser) {
        Assert.notNull(parser, "ExpressParser parser must not be null");
        String key = parser.parserKey();
        if (StrUtil.isBlank(key)) {
            return;
        }
        PARSER_MAP.put(key, parser);
        log.info("ExpressParser[key={}, type={}] has been found", key, parser.parserType());
    }


}
