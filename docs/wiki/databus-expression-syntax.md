# 参数表达式语法契约：`{{ }}` 动态标记与引擎演进路线

> 2026-09-24 拍板。本篇是**规范档**——定义参数值里"什么算动态、怎么写、怎么求值"的对外契约。
> 与 [databus-formula-engine-research.md](databus-formula-engine-research.md)（**调研档**，回答"要不要自建表达式引擎"）互为引用，不重复论证。
> 本篇不含代码改动，落地清单见 §7。

## 1. 结论（TL;DR）

**动态标记统一为 `{{ }}`，且只有这一种规则。** 同时废止两种既有写法：裸 `$.path` 与 `${$.path}`。

判定规则一句话：**参数值里出现 `{{` 就是动态，没出现就是字面量。**

求值规则三条：

| 参数值形态 | 行为 | 产物类型 |
| :--- | :--- | :--- |
| 整字段恰为单个 `{{ 表达式 }}` | 求值 | **保留原始类型**（对象 / 数组 / 数字 / 布尔不被字符串化） |
| `{{ }}` 嵌在文本中间（含多个） | 逐个求值后拼接 | 字符串 |
| 不含 `{{` | 原样传递 | 字面量 |

**引擎不变，只换标记**：`{{ }}` 内当前仍由 jayway JSONPath 求值。将来引入表达式引擎（储备 QLExpress4）是纯内部实现替换，**用户配置零迁移**——这是选 `{{ }}` 而非 `${}` 或 Step Functions 后缀方案的核心理由。

## 2. 为什么要改：现状的三个真问题

现状实现在 [PathResolver](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java)，同时支持两种写法：

```java
// PathResolver.java:26 —— 纯路径
private static final Pattern PURE_PATH_PATTERN = Pattern.compile("^\\$\\.[\\w.$\\[\\]]+$");

// PathResolver.java:36-37 —— 混合模板，group(1)=${$.path}，group(2)=裸路径
private static final Pattern PATH_FRAGMENT_PATTERN =
    Pattern.compile("\\$\\{(\\$\\.[\\w.$\\[\\]]+)\\}|(\\$\\.[\\w.$\\[\\]]+)");
```

种子数据里两种写法**混着用**——同一条 `bpm-flow` 链路（[databus_chain_mock_data.sql:20](../../script/sql/databus_chain_mock_data.sql)）：

```jsonc
"password": "$.request.password",          // 裸路径
"title":    "申请-${$.request.code}"        // 花括号模板
```

### 问题 ①：两套写法语义重叠，规则不唯一

`$.a.b` 和 `${$.a.b}` 在混合模板里等价，用户无从判断该用哪个。项目记忆曾约定"新配置只用裸路径、`${}` 留作兼容"，但兼容分支的存在本身就意味着解析器要维护两条路径、文档要写两遍、前端提示要给两种示例。

### 问题 ②：混合模板产物永远是字符串

[resolveMixedPath](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java#L101) 走 `Matcher.appendReplacement` 拼 `StringBuffer`，返回值固定 `String`。所以：

- `"dataPath": "$.fieldMap1"` —— 纯路径，`resolve()` 走 `read()`，**能拿回对象**
- `"title": "申请-${$.request.code}"` —— 混合模板，**只能拿回字符串**

一旦字段里有任何非路径文本，整个值就被字符串化，取不回结构化数据。这是**能力缺口**，不只是写法不统一。

### 问题 ③：`contains("$.")` 是裸子串判断，会误伤字面量

```java
// PathResolver.java:55-60
public static boolean containsPathExpression(String value) {
    if (value == null) return false;
    return value.contains("$.");
}
```

任何**本意是字面量**、但恰好含 `$.` 的字符串都会被当动态解析：SQL 片段、正则、给用户看的说明文案、价格文本。`resolveMixedPath` 里路径读不到时 `toReplacementString` 会**保留原片段**（[PathResolver.java:157](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java#L144)），所以不会报错——**静默地不替换**，比报错更难查。

改成 `{{ }}` 后，判定条件从"含 `$.` 子串"变成"含 `{{`"，误伤面大幅收窄（`{{` 在业务字面量里几乎不出现）。

## 3. 业界三派与选型依据

| 流派 | 代表产品 | 特征 | 对本项目 |
| :--- | :--- | :--- | :--- |
| **`{{ }}`** | n8n / Zapier / Make / Node-RED / Airflow(Jinja) | 低代码自动化事实标准，业务用户已被这批产品教育过 | ✅ **选定** |
| `${ }` | Spring 占位符 / Camunda 7 UEL / Jenkins / Groovy GString | 程序员符号；**在本技术栈已被 Spring 与 Groovy 占用语义**，混用生歧义 | ❌ |
| 字段名后缀 `.$` + `States.Format()` | AWS Step Functions | 类型最干净（值 / 路径在**字段名**上区分，不靠猜内容），但业务用户不直观 | ❌ |

### 为什么 `${ }` 在本栈特别危险

本项目同时存在：

- **Spring**：`@Value("${...}")`、`application.yml` 占位符
- **Groovy 脚本节点**：GString `"用户${name}"`（[databus-script-component.md](databus-script-component.md) 已上线）
- **LiteFlow EL**：字符串字面量（且 2.16.x 有 normalize 篡改前科，见 [liteflow-el-normalize-bug.md](liteflow-el-normalize-bug.md)）

参数值再叠一层 `${}`，四处同形符号，读配置的人无法判断该由谁解析。`{{ }}` 在本栈**未被任何一层占用**。

### 为什么不用 Step Functions 的后缀方案

后缀方案（`"title.$": "$.request.code"`）类型语义最干净——路径与值在字段名层面就分开，永不误判。但：

1. 业务用户看不见"字段名"这一层，配置表单里要额外解释
2. **无法承载"文本 + 表达式"混排**（`申请-{code}` 这种），只能退回 `States.Format()` 函数调用，更绕
3. **对引擎升级不前向兼容**：后缀方案的语义被钉死在"这是一个 JSONPath"，将来要在同一位置支持 `{{ $.age >= 18 ? '成年' : '未成年' }}` 就得换契约

`{{ }}` 的关键优势是**前向兼容**：今天 `{{ $.a.b }}` 由 jayway 求值，将来 `{{ 表达式 }}` 由引擎求值，**同一标记、同一位置、用户配置不动**。

## 4. 语法规范（正式定义）

### 4.1 词法

```
参数值     := 字面量 | 动态串
动态串     := 片段+
片段       := 文本 | 表达式
表达式     := "{{" 内容 "}}"
内容       := 不含 "}}" 的任意字符序列（首尾空白忽略）
```

### 4.2 整字段 vs 嵌入的判定

**整字段**＝参数值 trim 后**恰好**是一个完整表达式，前后无其它字符。

实现注意（避免踩坑）：不能用贪婪正则 `^\{\{(.+)\}\}$` 判定，否则 `{{ $.a }} {{ $.b }}` 会被误判为"整字段"，内层捕获到 `$.a }} {{ $.b`。正确做法二选一：

- 用非贪婪 + 全文匹配：`^\{\{(.*?)\}\}$` 且校验捕获内容不含 `}}`
- 或先扫描配对：找到第一个 `{{` 的配对 `}}`，检查它是否就是字符串末尾

### 4.3 求值与类型保留

| 输入 | 判定 | 输出 |
| :--- | :--- | :--- |
| `{{ $.fieldMap1 }}` | 整字段 | `Map` 对象（**原类型**） |
| `{{ $.httpRequest1.response.code }}` | 整字段 | `Integer` 200 |
| `{{ $.request.files }}` | 整字段 | `List` |
| `申请-{{ $.request.code }}` | 嵌入 | `String` `"申请-A001"` |
| `{{ $.a }}/{{ $.b }}` | 嵌入（两个表达式） | `String` 拼接 |
| `http://x.com/a` | 无 `{{` | 字面量原样 |
| `$.request.password` | **无 `{{`** | **字面量原样**（⚠️ 行为变更，见 §6） |

嵌入场景的字符串化规则沿用 [toReplacementString](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java#L144)：`null`→`"null"`、字符串→转义双引号后拼接、数字/布尔→`toString`、集合/Map/数组→JSON 序列化。

### 4.4 循环变量的位置

`$i / $j / $k`（三层，`MAX_LOOP_DEPTH = 3`，见 [databus-loop-component.md](databus-loop-component.md)）写在表达式**内部**：

```jsonc
"source": "{{ $.groups[$i].users[$j].name }}"
```

第 1 步（本篇）**不改循环变量机制**，仍由 [substituteLoopVars](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L435) 在求值前做字符串替换。第 2 步引入引擎后改为**注入求值作用域的真变量**，下标写错从运行时炸提前到解析期炸（§5.3）。

### 4.5 明确不支持（划边界）

- **嵌套表达式**：`{{ {{ $.a }} }}` 不支持，内层按普通文本处理
- **转义**：第 1 步不提供"我要输出字面量 `{{`"的转义机制（业务参数里出现 `{{` 的概率极低，出现再补）
- **跨字段引用**：表达式只在**单个参数值**内求值，不做字段间依赖分析

## 5. 引擎演进路线：语法先行，引擎后置

### 5.1 三层能力模型（不变）

沿用 [databus-formula-engine-research.md](databus-formula-engine-research.md) 拍板的三层，禁止绕过三层新增私有 DSL：

1. **结构化原子组件** —— 已有（condition 10 操作符、setValue、fieldMap 等 15 个组件），零语法门槛
2. **参数槽内联表达式** —— **缺口，本篇的标记就是为它预留的插槽**
3. **Groovy 脚本节点** —— 已有，承接长尾与多行逻辑

### 5.2 三步走

| 步 | 时机 | 做什么 | 用户可见变化 |
| :--- | :--- | :--- | :--- |
| **第 1 步** | **现在** | 落 `{{ }}` 标记，求值仍走 jayway JSONPath | 写法变；能力上多了"整字段保留原类型" |
| 第 2 步 | 触发信号出现后 | 引 QLExpress4，落点 [DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L250)，`{{ }}` 内先由 jayway 解路径、再由引擎算表达式；函数白名单 + 求值超时 | 能在参数里写 `{{ $.amount * 0.8 }}`、三元、字符串函数 |
| 第 3 步 | 第 2 步稳定后 | 前端字段自动补全 + 求值结果实时预览 | 编辑器体验对齐 n8n |

**触发信号**（沿用 2026-09-20 调研定义，不提前开工）：

- 旧 meta 公式频度摸底出现**高频一行计算**
- 或实测中"为一行三元表达式拖一个 Groovy 节点"的摩擦被用户**明确抱怨**

### 5.3 引擎选型：QLExpress4，不用 GraalJS

完整横评见 [databus-formula-engine-research.md](databus-formula-engine-research.md) §5，此处只记结论与本次新增的否决理由：

| 引擎 | 新增依赖 | 协议 | 结论 |
| :--- | :--- | :--- | :--- |
| **QLExpress4 4.1.0** | **零**（随 `liteflow-core:2.16.1.3` 传递进 classpath，本地 `.m2/repository/com/alibaba/qlexpress4/4.1.0/` 已核实） | Apache-2 | ✅ **推荐** |
| SpEL | 零（Spring Boot 自带） | Apache-2 | 备选，默认能调任意 Java 方法，须白名单收紧 |
| Aviator | 1 jar | Apache-2 | 备选，语法非主流 |
| GraalJS | 重（truffle-api + js-language + icu4j） | GPL2+CE | ❌ **不推荐** |

**为什么明确否决 GraalJS**（2026-09-24 补充）：它是 JDK17/21 上唯一的真 JS 选项（`liteflow-script-javascript` 仅 ES5 且限 JDK8），语法手感最好——但为这份手感要付出：整套 polyglot 运行时依赖、必须自配沙箱边界（`allowAllAccess=false` + 禁 Java 类查找，配错就是 RCE）、非 GraalVM JDK 上解释执行开销大。**为芝麻丢西瓜。**

**第 2 步的三条实现约束**（引擎无关，现在就记下来）：

1. **jayway 仍是唯一数据权威**：表达式引擎只能读、不能存，它是计算器不是第二个数据库。避免"两份数据谁是准的"
2. **表达式禁副作用**：不许写文件、不许发 HTTP、不许改数据树。要副作用请用 Groovy 脚本节点——n8n 也是用"表达式 vs Code 节点"这条线治理复杂度
3. **注册 `jsonpath()` 辅助函数**：给表达式留逃生口，需要递归下拉 `$..records` 或条件过滤 `[?(@.age>18)]` 时在表达式里调它。这样对 n8n 的表达力是**超集**（n8n 要写 JS 循环，这里一行 JSONPath）

## 6. 常见坑

| 坑 | 说明 |
| :--- | :--- |
| **裸路径不再自动解析** | 改后 `$.request.password` 是**字面量**，会被原样传给下游。所有既有配置必须补 `{{ }}`。这是**破坏性变更**，靠"设计期无兼容包袱 + 重新生成 27 条种子 mock"消化，不留兼容分支（留了就退回问题 ①） |
| 整字段判定用贪婪正则 | 见 §4.2，`{{ $.a }} {{ $.b }}` 会被误判为整字段 |
| 循环变量与 `{{ }}` 的处理顺序 | `substituteLoopVars` 必须在表达式求值**之前**跑（现状即如此），否则 `[$i]` 进 jayway 会解析失败 |
| `${}` 兼容分支不要"顺手保留" | 保留就意味着解析器双路径、文档双写、前端双示例，问题 ① 原样复现。要删干净 |
| 混合模板的静默不替换 | 现状路径读不到时保留原片段、不报错（[PathResolver.java:157](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java#L144)）。改 `{{ }}` 时**建议一并改为可诊断**：至少 `log_level != OFF` 时打 WARN，否则排障仍然靠猜 |

## 7. 落地清单（第 1 步）

按依赖顺序：

1. **[PathResolver](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java)**
   - `PATH_FRAGMENT_PATTERN` 改为匹配 `{{ ... }}`，删除 group(2) 裸路径分支
   - `containsPathExpression` 从 `contains("$.")` 改为 `contains("{{")`
   - 新增"整字段"判定方法（§4.2 的配对扫描），供 `resolve()` 决定走原类型还是拼接
   - `PURE_PATH_PATTERN` / `isPureJsonPath` 的对外职责收缩为内部实现细节（不再有"纯路径参数值"这个概念）
2. **[DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L250)** —— 三分支（`directLoopIndex` / `isPureJsonPath` / `isMixedPathString`）收敛为"有无 `{{`"一个判断 + 整字段/嵌入二分
3. **[databus_chain_mock_data.sql](../../script/sql/databus_chain_mock_data.sql)** —— 27 条链路全部重新生成，所有动态参数补 `{{ }}`。重点核对：`bpm-flow` 的 `$.request.password`、`serial-all` 的 `$.httpRequest1.response.msg`、`iterator-nested` 的 `$.groups[$i].users[$j].name`
4. **前端** —— [CmpProps.vue](../../../plus-ui/src/views/databus/editor/components/CmpProps.vue) 的 `DATA_HINTS` 与 [cmp-defs.ts](../../../plus-ui/src/views/databus/editor/cmp-defs.ts) 的示例文案，全部改成 `{{ }}` 写法
5. **组件内取值点** —— 各组件里直接调 `read("$.xxx")` 取**自身配置**的地方不受影响（那是代码内硬编码路径，不是用户配置）；只有从**用户参数**里拿到的字符串要走 `resolve()`。改前 grep 一遍确认没有绕过 `resolve()` 的
6. **文档同步** —— [databus-context-design.md](databus-context-design.md) §3.1「路径四种类型与 resolve() 分发」、§3.2「混合路径解析」需按新契约改写；`.trae/project_memory.md` §四 已更新
7. **验证边界** —— 零成本静态检查（`mvnw compile`、IDE 诊断、oxlint、vue-tsc）+ 代码审查；测试由用户亲跑亲判（项目约定，AI 不自行运行测试）

## 8. 关键代码位置索引

| 关注点 | 位置 |
| :--- | :--- |
| 路径正则与混合模板解析 | [PathResolver.java](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java)（`PURE_PATH_PATTERN` L26、`PATH_FRAGMENT_PATTERN` L36、`resolveMixedPath` L101、`toReplacementString` L144） |
| 参数求值总分发 | [DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L250) |
| 循环变量替换 | [DatabusContext.substituteLoopVars](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L435)、`directLoopIndex` L422 |
| 第 2 步引擎落点 | [DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java#L250)（唯一改动点） |
| 前端提示文案 | [CmpProps.vue](../../../plus-ui/src/views/databus/editor/components/CmpProps.vue) `DATA_HINTS`、[cmp-defs.ts](../../../plus-ui/src/views/databus/editor/cmp-defs.ts) |
| 种子链路数据 | [databus_chain_mock_data.sql](../../script/sql/databus_chain_mock_data.sql) |

## 9. 关联文档

- [databus-formula-engine-research.md](databus-formula-engine-research.md) —— 为什么不自建公式引擎、JVM 引擎横评、触发信号定义
- [databus-context-design.md](databus-context-design.md) —— DatabusContext 读写机制与动态变量决策（本篇 §7 第 6 项要同步改）
- [databus-context-concurrency.md](databus-context-concurrency.md) —— 同一棵共享树的并发安全（与本篇同批讨论产出，P0 优先级高于本篇落地）
- [databus-script-component.md](databus-script-component.md) —— 第三层能力（Groovy），以及 GString 为什么占用了 `${}`
- [n8n-vs-databus-paradigm.md](n8n-vs-databus-paradigm.md) —— n8n 的 `{{ }}` 与 `$json` 到底是什么、与本项目范式的差异
