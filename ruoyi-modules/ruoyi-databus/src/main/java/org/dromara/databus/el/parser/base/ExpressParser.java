package org.dromara.databus.el.parser.base;

import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.enums.ExpressParserEnum;
import com.yomahub.liteflow.enums.ConditionTypeEnum;
import com.yomahub.liteflow.flow.element.Chain;
import com.yomahub.liteflow.flow.element.Condition;

import java.util.List;

/**
 * EL 表达式解析器统一接口 —— 整个 el 包的"设计总纲"。
 * <p>
 * 每种 EL 关键字（THEN/WHEN/IF/...）对应一个实现类（见 parser.el 包），
 * 每个解析器都具备<b>双向能力</b>：
 * <ol>
 *   <li><b>EL → JSON（builder* 系列方法）</b>：把 LiteFlow 解析出的
 *       {@link Condition} 对象树转成画布 JSON 的 {@link CmpProperty} 树；</li>
 *   <li><b>JSON → EL（generate* 系列方法）</b>：把 {@link CmpProperty} 树
 *       按本关键字自己的语法模板拼回 EL 字符串。</li>
 * </ol>
 * <p>
 * 下半部分的 String 常量是 JSON→EL 用的<b>语法模板</b>，其中 {@code {}}
 * 是占位符（由 hutool 的 StrUtil.format 填充）。生成一段 EL 的固定流程：
 * <pre>
 * THEN({})  ──填条件──▶  THEN({})  ──填组件──▶  THEN(a, b, c)
 *        ──拼属性──▶  THEN(a, b, c).id("dog")  ──补分号──▶  THEN(a, b, c);
 * </pre>
 * 对应接口中的 5 个 generate 方法（见 {@link AbstractExpressParser#abstractGenerateEL}）。
 *
 * @author <a href="mailto:dogsong99@163.com">dosong</a>
 * @since 2024/4/18
 */
public interface ExpressParser {

    // ==================== 解析器身份 ====================

    /**
     * 本解析器负责的 LiteFlow Condition 类型，作为注册到
     * {@code ExpressParserFactory.PARSER_MAP} 的 key。
     * @see ConditionTypeEnum
     */
    ConditionTypeEnum parserType();

    /**
     * 反向映射：给定 LiteFlow 的 Condition，返回它对应的 EL 关键字枚举。
     * （如 AndOrCondition 可能是 AND 也可能是 OR，由实现类自行判断。）
     */
    ExpressParserEnum getExpressType(Condition condition);

    // ==================== EL → JSON（builder* 方法） ====================

    /**
     * 构造本表达式的 VO 外壳：填 type + properties（不含 condition/children）。
     */
    CmpProperty builderVO(Condition condition);

    /**
     * 把被引用的子编排链转成 type=CHAIN 的 CmpProperty（含其内部 condition 列表）。
     */
    CmpProperty buildChildrenChain(Chain chain);

    /**
     * 构造"条件位"：IF/SWITCH/FOR/WHILE/ITERATOR/CATCH 的控制节点。
     * 没有条件位的关键字（THEN/WHEN/AND/OR/NOT）返回 null 或空对象。
     */
    CmpProperty builderCondition(Condition condition);

    /**
     * 构造子分支列表：THEN 的顺序子项、IF 的真假分支、SWITCH 的 to 列表等。
     */
    List<CmpProperty> builderChildren(Condition condition);

    // ==================== EL 语法模板（{} 为占位符） ====================

    /** 子项分隔符：THEN(a, b, c) 里的 ", " */
    String elSeparate = ",";

    /** 占位符，由 StrUtil.format 填充 */
    String elPlaceholder = "{}";

    /** 表达式属性模板：.id("xxx") */
    String elExpressId = ".id(\"{}\")";

    /** 表达式属性模板：.tag("xxx") */
    String elExpressTag = ".tag(\"{}\")";

    /** 表达式属性模板：.data("xxx") */
    String elExpressData = ".data(\"{}\")";

    /** 节点属性模板：a.tag("xxx")（普通节点把属性紧跟在 id 后面） */
    String elNodeTag = ".tag(\"{}\")";

    /** 节点属性模板：a.data("xxx") */
    String elNodeData = ".data(\"{}\")";

    /** EL 语句结束符 */
    String elEnd = ";";

    /** 串行模板：THEN({}) */
    String elThenMethod = "THEN({})";

    /** 并行模板：WHEN({}) */
    String elWhenMethod = "WHEN({})";

    /** 条件分支模板：IF(条件, true分支, false分支) */
    String elIfMethod = "IF({},{})";

    /** 异常捕获模板（无 DO）：CATCH(try块) */
    String elCatchMethod = "CATCH({})";

    /** 异常捕获模板（带 DO）：CATCH(try块).DO(catch块) */
    String elCatchDoMethod = "CATCH({}).DO({})";

    /** 布尔与模板：AND({}) */
    String elAndMethod = "AND({})";

    /** 布尔或模板：OR({}) */
    String elOrMethod = "OR({})";

    /** 布尔非模板：NOT({}) */
    String elNotMethod = "NOT({})";

    /** 选择模板：SWITCH(选择器).to(分支列表) */
    String elSwitchMethod = "SWITCH({}).to({})";

    /** 计数循环模板：FOR(计数器).DO(循环体) */
    String elForMethod = "FOR({}).DO({})";

    /** 条件循环模板：WHILE(条件).DO(循环体) */
    String elWhileMethod = "WHILE({}).DO({})";

    /** 迭代循环模板：ITERATOR(迭代器).DO(循环体) */
    String elIteratorMethod = "ITERATOR({}).DO({})";

    /** 循环跳出模板（追加在循环表达式尾部）：.BREAK(布尔节点) */
    String elBreakMethod = ".BREAK({})";

    // ==================== JSON → EL（generate* 方法） ====================

    /**
     * 生成 EL 的总入口（模板法五步曲），返回以分号结尾的完整表达式。
     */
    String builderEL(CmpProperty jsonEl);

    /**
     * 第 1 步：返回本关键字的外层函数模板，如 THEN({})、SWITCH({}).to({})。
     */
    String generateELMethod(CmpProperty jsonEl);

    /**
     * 第 2 步：填充条件位（第一个 {}），如 IF({},{}) → IF(a,{})。
     * THEN/WHEN 等无条件位的关键字原样返回。
     */
    String generateCondition(CmpProperty jsonEl, String elExpress);

    /**
     * 第 3 步：填充子分支（剩余 {}），如 IF(a,{}) → IF(a, b, c)。
     */
    String generateCmp(CmpProperty jsonEl, String elExpress);

    /**
     * 第 4 步：拼接表达式自身的 id/tag/data 属性。
     */
    String generateIdAndTag(CmpProperty jsonEl, String elExpress);

    /**
     * 第 5 步：收尾——循环类先追加 .BREAK(...)，最后补分号。
     */
    String generateELEnd(CmpProperty jsonEl, String elExpress);

}
