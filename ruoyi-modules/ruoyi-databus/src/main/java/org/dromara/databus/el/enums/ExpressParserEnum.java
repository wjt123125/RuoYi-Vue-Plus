package org.dromara.databus.el.enums;

import com.yomahub.liteflow.common.ChainConstant;
import lombok.Getter;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * EL 关键字枚举：画布 JSON 中 type 属性的"表达式关键字"取值（大写）。
 * <p>
 * 区分两个"类型"概念：
 * <ul>
 *   <li>{@code ExpressParserEnum.type} —— EL 关键字（THEN/IF/SWITCH...），
 *       即 CmpProperty.type 在<b>表达式</b>上的取值；</li>
 *   <li>LiteFlow 的 {@code ConditionTypeEnum} —— LiteFlow 内部对 Condition
 *       实现类的类型标记（如 then/switch/if_and_or_opt），是<b>解析器注册</b>用的 key。</li>
 * </ul>
 * 两者通过各解析器的 {@code parserType()}（返回 ConditionTypeEnum）与
 * {@code getExpressType()}（返回本枚举）互相映射。
 * 取值来源引用 {@link ChainConstant}，保证与 LiteFlow 官方常量一致。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/24
 */
@Getter
public enum ExpressParserEnum {

    /** 串行编排：THEN(a, b, c) */
    THEN(ChainConstant.THEN, "THEN"),
    /** 并行编排：WHEN(a, b, c) */
    WHEN(ChainConstant.WHEN, "WHEN"),

    /** 选择编排：SWITCH(x).to(a, b) */
    SWITCH(ChainConstant.SWITCH, "SWITCH"),
    /** 计数循环：FOR(x).DO(...) */
    FOR(ChainConstant.FOR, "FOR"),
    /** 迭代循环：ITERATOR(x).DO(...) */
    ITERATOR(ChainConstant.ITERATOR, "ITERATOR"),
    /** 异常捕获：CATCH(...).DO(...) */
    CATCH(ChainConstant.CATCH, "CATCH"),
    /** DO 块（跟随 FOR/WHILE/ITERATOR/CATCH 使用） */
    DO(ChainConstant.DO, "DO"),

    /** 条件循环：WHILE(x).DO(...) */
    WHILE(ChainConstant.WHILE, "WHILE"),
    /** 条件分支：IF(x, a, b) */
    IF(ChainConstant.IF, "IF"),

    /** 布尔与：AND(a, b) */
    AND(ChainConstant.AND, "AND"),
    /** 布尔或：OR(a, b) */
    OR(ChainConstant.OR, "OR"),
    /** 布尔非：NOT(a) */
    NOT(ChainConstant.NOT, "NOT"),

    /** 被引用的子编排链（buildChildrenChain 生成的占位单元） */
    CHAIN(ChainConstant.CHAIN, "CHAIN"),
    ;

    /** EL 关键字本体，如 "THEN"，与 ChainConstant 保持一致 */
    private String type;

    /** 描述（当前与 type 相同） */
    private String desc;

    ExpressParserEnum(String type, String desc) {
        this.type = type;
        this.desc = desc;
    }

    /**
     * 按 EL 关键字查找枚举，找不到直接抛异常（说明传入了不支持的 type）。
     */
    public static ExpressParserEnum of(String type) {
        Objects.requireNonNull(type);

        return Stream.of(values())
                .filter(bean -> bean.type.equals(type))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException(type + " not exists!"));
    }
}
