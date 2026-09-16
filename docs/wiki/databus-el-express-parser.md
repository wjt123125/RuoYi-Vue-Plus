# Databus EL 引擎：LiteFlow EL 表达式与画布 JSON 双向转换

> 所属模块：`ruoyi-databus`，包路径 `org.dromara.databus.el`
> 更新日期：2026-09-13
> 参考实现：liteflow-editor-core

## 1. 这个模块解决什么问题？

Databus 的编排设计器（画布）和 LiteFlow 引擎说的是两种"语言"：

- **前端画布**需要一个结构化的 JSON 树来渲染节点和连线；
- **LiteFlow 引擎**只认 EL 表达式字符串（如 `THEN(a, SWITCH(b).to(c, d));`）来执行编排。

所以两边之间需要一个**双向翻译器**：

```
             生成 EL（generateEL / verifyELExpression）
  ┌──────────────────────────────────────────────────┐
  │                                                  │
  ▼                                                  │
前端画布 JSON 树 (CmpProperty)                     LiteFlow EL 字符串
  ▲                                                  │
  └──────────────────────────────────────────────────┘
             解析 EL（generateJsonEL）
```

| 方向 | 入口方法 | 用途 |
| --- | --- | --- |
| JSON → EL | `ExpressGenerator.generateEL(CmpProperty)` | 画布保存时，把用户在画布上搭的结构翻译成 EL 存库 |
| JSON → EL + 校验 | `ExpressGenerator.verifyELExpression(CmpProperty)` | 保存前做"语法体检" |
| EL → JSON | `ExpressGenerator.generateJsonEL(ELInfo)` | 打开编排/回显时，把库里存的 EL 翻译成画布 JSON |

对应的 REST 接口：`ElGenerateController`（`/databus/el/generate`）、`DatabusEditorController`、`DatabusChainServiceImpl`。

## 2. 五分钟看懂核心模型：CmpProperty

整个模块围绕一棵 `CmpProperty` 树转来转去。**看懂这个类，代码就看懂了一半**。

每个 `CmpProperty` = 一个 EL 语法单元：

```jsonc
{
  "type": "...",        // ① 这个单元是什么
  "properties": {...},  // ② 挂在上面的属性（可选）
  "condition": {...},   // ③ "条件位"控制节点（可选）
  "children": [...]     // ④ 子分支列表（可选）
}
```

### ① type 的两种取值（重要！）

| 取值种类 | 例子 | 含义 |
| --- | --- | --- |
| **EL 关键字** | `THEN` / `WHEN` / `IF` / `SWITCH` / `FOR` / `WHILE` / `ITERATOR` / `CATCH` / `AND` / `OR` / `NOT` / `CHAIN` | 这是一个"表达式"（编排结构） |
| **节点类型** | `NodeComponent` / `NodeSwitchComponent` / `NodeBooleanComponent` / `NodeForComponent` / `NodeWhileComponent` / `NodeIteratorComponent` | 这是一个"组件节点"（真正干活/判断的组件），值来自 LiteFlow `NodeTypeEnum` 的映射类简称 |

### ③ condition（条件位）——最容易困惑的字段

`condition` 只在"这个表达式需要被某个控制节点驱动"时才有值：

| 表达式 | condition 是什么 |
| --- | --- |
| `IF(x, ...)` | 判断节点 x |
| `SWITCH(x).to(...)` | 选择器节点 x |
| `FOR(x).DO(...)` | 计数器节点 x |
| `WHILE(x).DO(...)` | 布尔条件节点/子表达式 x |
| `ITERATOR(x).DO(...)` | 迭代器节点 x |
| `CATCH(x).DO(y)` | try 块 x（**注意：CATCH 的条件位是 try 块，children 才是 catch 块**） |
| `THEN / WHEN / AND / OR / NOT` | 没有，为 null（WHEN 是空对象 `{}`，语义等价） |

### ④ children 的形状因关键字而异

| 关键字 | children 内容 |
| --- | --- |
| `THEN` / `WHEN` | 顺序/并行的子项列表 |
| `IF` | `[true分支, false分支?]`（假分支可没有） |
| `SWITCH` | `.to()` 的候选分支列表 |
| `FOR` / `WHILE` / `ITERATOR` | `[DO内容, BREAK包装节点?]`（BREAK 包一层 type=BREAK 的节点，真正的布尔节点在其 children 里） |
| `CATCH` | `[catch块?]`（最多一个） |
| `AND` / `OR` | 恰好 2 个子项 |
| `NOT` | 恰好 1 个子项 |

### 一个完整例子

EL：`IF(andNode, THEN(a, b), c);`

对应 JSON 树：

```jsonc
{
  "type": "IF",
  "condition": { "id": "andNode", "type": "NodeComponent" },  // 布尔判断节点
  "children": [
    { "type": "THEN", "children": [
        { "id": "a", "type": "NodeComponent" },
        { "id": "b", "type": "NodeComponent" }
    ]},
    { "id": "c", "type": "NodeComponent" }
  ]
}
```

> 判断技巧：**`id == null` 的一般是表达式（THEN/IF...），`id != null` 的一般是组件节点**。生成 EL 时就靠这个区分走递归还是拼 id。

## 3. 代码地图（按推荐阅读顺序）

```
org.dromara.databus.el
├── bean
│   ├── CmpProperty.java        ① 先读：画布树节点，一切的核心模型
│   ├── Properties.java         ② id/tag/data 三件套
│   └── ELInfo.java             ③ chainId + elStr 的输入输出信封
├── enums
│   └── ExpressParserEnum.java  ④ EL 关键字枚举（JSON 里 type 的表达式取值）
├── parser
│   ├── base
│   │   ├── ExpressParser.java              ⑤ 接口：定义双向能力 + 全部 EL 语法模板常量
│   │   ├── AbstractExpressParser.java      ⑥ 核心：五步曲主干 + 递归枢纽 + Node/Chain 转换
│   │   ├── AbstractLoopExpressParser.java  ⑦ 循环家族（FOR/WHILE/ITERATOR）公共逻辑
│   │   └── AbstractAndOrNotExpressParser.java  ⑧ 布尔家族（AND/OR/NOT）标记基类
│   ├── factory
│   │   └── ExpressParserFactory.java       ⑨ 启动时自动收集注册所有解析器
│   ├── selector
│   │   └── ParserSelector.java             ⑩ 按类型路由到对应解析器
│   ├── generator
│   │   └── ExpressGenerator.java           ⑪ 门面：对外两个方向的入口
│   └── el                                  ⑫ 每种关键字一个实现：
│       ├── ThenConditionParser.java          THEN 串行
│       ├── WhenConditionParser.java          WHEN 并行
│       ├── IfConditionParser.java            IF 条件分支
│       ├── SwitchConditionParser.java        SWITCH 选择
│       ├── ForConditionParser.java           FOR 计数循环
│       ├── WhileConditionParser.java         WHILE 条件循环
│       ├── IteratorConditionParser.java      ITERATOR 迭代循环
│       ├── CatchConditionParser.java         CATCH 异常捕获
│       ├── AndOrConditionParser.java         AND/OR 共用
│       └── NotConditionParser.java           NOT 布尔非
```

## 4. 方向一：JSON → EL（生成表达式）

### 4.1 模板法五步曲

生成一段 EL 不是拼接字符串的"手工作坊"，而是**所有关键字统一的五步节奏**（定义在 `AbstractExpressParser#abstractGenerateEL`）。每个解析器只负责回答"我的模板长什么样、每一步怎么填"：

| 步骤 | 接口方法 | THEN 的例子 | IF 的例子 |
| --- | --- | --- | --- |
| 1. 取模板 | `generateELMethod` | `THEN({})` | `IF({},{})` |
| 2. 填条件位 | `generateCondition` | 无条件位，原样返回 | 填第一个 {}：`IF(a,{})` |
| 3. 填子分支 | `generateCmp` | 填 {}：`THEN(b, c)` | 填第二个 {}：`IF(a, b, c)` |
| 4. 拼属性 | `generateIdAndTag` | 追加 `.id("dog").tag("x")` | 同左 |
| 5. 收尾 | `generateELEnd` | 补分号 `;` | 补分号 `;` |

循环类（FOR/WHILE/ITERATOR）的第 5 步特殊：先追加 `.BREAK(d)` 再补分号。

### 4.2 递归枢纽：generateNodeComponent

第 3 步填子分支时，每个子项走 `AbstractExpressParser#generateNodeComponent`，这是**唯一需要重点理解的递归点**：

```
generateNodeComponent(child)
├── child.id == null  →  是子表达式（THEN/IF/AND...）
│                        递归调用 abstractGenerateEL(child) 生成整段，如 "THEN(b,c)"
└── child.id != null  →  是组件节点
    ├── type == NodeComponent（普通节点）  → 拼 "a.tag(\"x\").data(\"y\")"
    └── type == NodeBooleanComponent（布尔节点） → 只拼 "d"
```

走一遍完整例子，`THEN(a, IF(b, c));` 这棵树是怎么拼出来的：

```
THEN({})                                  ← 第1步，根是 THEN
THEN({}) 填 children：
  ├─ child a：id!=null、NodeComponent  →  "a"
  └─ child IF：id==null → 递归！
  │    IF({},{})                        ← 子表达式的第1步
  │    IF(b,{})                         ← 第2步，填布尔节点 b
  │    IF(b, c)                         ← 第3步
  │    → "IF(b, c)"
  → THEN(a, IF(b, c))                   ← 掐掉多余逗号后 format
THEN(a, IF(b, c));                       ← 第5步补分号
```

### 4.3 data 的转义

节点的 `data` 是组件参数（通常是 JSON 字符串）。拼进 `.data("...")` 前必须做 Java 字符串转义（`AbstractExpressParser#escapeJava`），否则 data 里的双引号会破坏 EL 的引号结构导致整个表达式非法。

## 5. 方向二：EL → JSON（解析表达式）

`ExpressGenerator#generateJsonEL` 的流水线：

```
EL 字符串
  │ ① 准备上下文：把 FlowBus 里所有 chain、所有 node、CURR_CHAIN_ID 塞进 Map
  │    （先 chain 后 node！node 优先，重名时覆盖 chain）
  │ ② QLExpress 执行：LiteFlow 把 THEN/SWITCH/... 注册成了 QLExpress 操作符，
  │    执行结果不是字符串，而是 LiteFlow 的运行时对象树（Condition/Node/Chain）
  ▼
Condition 对象树（LiteFlow 运行时结构）
  │ ③ ParserSelector 按根 Condition 的类型找到解析器
  │ ④ 递归三件套：builderVO(外壳) + builderCondition(条件位) + builderChildren(子分支)
  ▼
CmpProperty 树（画布 JSON）
```

EL→JSON 侧每个"子元素"的三分转换（`builderChildList` / 各 parser 的 builderChildren）：

- `Executable instanceof Condition` → 子表达式，递归 `builderChildVO`；
- `Executable instanceof Node` → 组件节点，走 `nodeMapper`（id + NodeTypeEnum 映射类简称 + tag/data）；
- `Executable instanceof Chain` → 被引用的子编排链，走 `buildChildrenChain`（转成 type=CHAIN 的单元，children 是链内 condition 列表）。

两个细节：

1. **默认 id 过滤**：LiteFlow 会给 Condition 塞默认 id（如 `condition-then`）。这不是用户设置的，`getPropertyId` 会把它抹成 null，避免画布显示脏数据。
2. **FallbackNode 特例**：降级节点没有常规 nodeId，用 `getExpectedNodeId()`（期望降级到的目标节点）作为画布 id。

## 6. 解析器的注册与路由（策略模式 + 自动装配）

```
Spring 启动
  └─ ExpressParserFactory（@Component）
       └─ @Autowired List<ExpressParser>   ← 收集所有 @Component 的解析器
            └─ PARSER_MAP[key = ConditionTypeEnum.getType(), value = 解析器]

任意时刻
  └─ ParserSelector.getParser(type 或 condition)
       └─ 遍历 PARSER_MAP，contains 模糊匹配 key → 返回解析器
```

要点：

- **新增一种 EL 关键字 = 新写一个解析器类 + `@Component`**，工厂自动注册，无需改任何现有代码；
- AND/OR 是特殊的：LiteFlow 里它们共用一个 Condition 类型（`TYPE_AND_OR_OPT`），所以**两个关键字共用 `AndOrConditionParser`**，运行时靠 `AndOrCondition#getBooleanConditionType()` 区分是 AND 还是 OR（EL→JSON 方向），或靠 `CmpProperty.type` 选模板（JSON→EL 方向）。

## 7. 快速参照：关键字 × 解析器 × 结构速查表

| EL 关键字 | 解析器 | 模板 | condition（条件位） | children | 特殊逻辑 |
| --- | --- | --- | --- | --- | --- |
| THEN | ThenConditionParser | `THEN({})` | null | 顺序子项 | 最基础的"列表型" |
| WHEN | WhenConditionParser | `WHEN({})` | 空对象 `{}` | 并行子项 | 与 THEN 同构 |
| IF | IfConditionParser | `IF({},{})` | 判断节点 | [true, false?] | 布尔节点/子表达式双分支 |
| SWITCH | SwitchConditionParser | `SWITCH({}).to({})` | 选择器节点 | to 列表 | — |
| FOR | ForConditionParser | `FOR({}).DO({})` | 计数器节点 | [DO, BREAK?] | 继承循环基类 |
| WHILE | WhileConditionParser | `WHILE({}).DO({})` | 布尔节点/子表达式 | [DO, BREAK?] | 继承循环基类 |
| ITERATOR | IteratorConditionParser | `ITERATOR({}).DO({})` | 迭代器节点 | [DO, BREAK?] | 继承循环基类 |
| CATCH | CatchConditionParser | `CATCH({})` 或 `CATCH({}).DO({})` | try 块 | [catch 块?] | 模板动态选择 |
| AND/OR | AndOrConditionParser | `AND({})` / `OR({})` | null | 恰好 2 个 | 两关键字共用一个类 |
| NOT | NotConditionParser | `NOT({})` | null | 恰好 1 个 | — |

循环类的 `.BREAK(d)`：children 里的 BREAK 是一个 `type=BREAK` 的包装节点，真正的布尔节点挂在它的 children 里；生成 EL 时由循环基类的 `generateELEnd → generateBreak` 追加 `.BREAK(d)`，且仅当内部是 `NodeBooleanComponent` 时才拼。

## 8. 常见疑问（FAQ）

**Q1：为什么 JSON→EL 第 3 步拼逗号时"先多拼一个再掐掉"？**
每个子项处理完统一补一个 `,`，最后 `substringBeforeLast` 掐掉最后一个，避免在循环里判断"是不是最后一项"，写法更简单。

**Q2：`generateJsonEL` 里为什么要往上下文塞所有 chain 和 node？**
QLExpress 执行 EL 时，表达式里的每个标识符（`THEN(a,b)` 里的 a、b）都是变量求值。LiteFlow 把这些"变量"解析成了 FlowBus 里注册的 Node/Chain 对象，所以必须提前放进 QLExpress 上下文。这也是为什么**节点必须先注册到 LiteFlow（FlowBus）里，EL 解析才能成功**。

**Q3：`verifyELExpression` 和 `generateEL` 有什么区别？**
前者生成后额外调用 `LiteFlowChainELBuilder.validate(elStr)` 做官方合法性校验；后者只生成不校验。

**Q4：type 里的 `CHAIN` 是什么？**
EL 里可以直接引用另一条链（如 `SWITCH(a).to(b, subChain);`）。解析时子链转成一个 `type=CHAIN` 的单元（id 为链 ID），画布上通常渲染成"引用节点"。

**Q5：为什么 WHEN 的 builderCondition 返回空对象而 THEN 返回 null？**
历史设计差异，语义上两者都表示"没有条件位"。序列化后 WHEN 表现为 `"condition": {}`、THEN 表现为 `"condition": null`，前端按空对象/null 都能兼容。

## 9. 扩展指南：新增一种 EL 关键字

假设 LiteFlow 新出了关键字 `XXX`，接入步骤：

1. 在 `ExpressParserEnum` 里加枚举值（引用 LiteFlow 的 `ChainConstant.XXX`）；
2. 新建 `XxxConditionParser extends AbstractExpressParser`（循环类则继承 `AbstractLoopExpressParser`）并加 `@Component`；
3. 实现 6 个方法：`parserType()`（注册 key）、`getExpressType()`、`generateELMethod()`（模板）、`generateCondition()`（条件位）、`generateCmp()`（子分支）、`generateIdAndTag()`；
4. 在 `ExpressGeneratorTest` 补两个用例：JSON→EL 生成正确、EL→JSON→EL 往返一致（参考 `roundTrip_elToJsonToEl_shouldBeConsistent`）。

工厂会自动注册新解析器，`ParserSelector` 自动路由，其余代码零改动。

## 10. 相关代码入口

| 入口 | 位置 |
| --- | --- |
| REST 接口 | `ElGenerateController`（POST `/databus/el/generate`） |
| 门面类 | `org.dromara.databus.el.parser.generator.ExpressGenerator` |
| 往返一致性测试 | `src/test/java/org/dromara/databus/ExpressGeneratorTest.java` |
| 冒烟测试组件 | `src/test/java/org/dromara/databus/component/`（hello/delay/boolean/iterator 等） |
