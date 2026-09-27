# 表达式引擎选型与循环变量机制调研

> 2026-09-25 会话产出。本文档用于并入既有 wiki（如 `databus-expression-syntax.md` / `databus-loop-component.md` / `databus-formula-engine-research.md`）。阅读对象：项目工程师、AI 协作助手。所有结论与既有 project_memory / roadmap 一致，不新增决策、不推翻既有结论。

---

## 一、表达式引擎选型：QLExpress4 vs GraalJS

### 1.1 候选清单（4 个，真正二选一的是 QL vs GraalJS）

| 引擎 | 新增依赖 | 协议 | 语法 | 评价 |
|:---|:---|:---|:---|:---|
| **QLExpress4 4.1.0** | **零**（随 `liteflow-core:2.16.1.3` 传递进 classpath） | Apache-2 | Java 风格 | **推荐** |
| SpEL | 零（Spring Boot 自带） | Apache-2 | Java 风格 | 备选，安全面大须收紧 |
| Aviator | 1 jar | Apache-2 | 自有语法 | 备选，语法非主流 |
| GraalJS | 重（truffle-api + js-language + icu4j） | GPL2+CE | 真 JS / ES6+ | **不推荐** |

### 1.2 QLExpress4 vs GraalJS 核心对比

| 维度 | QLExpress4 | GraalJS |
|:---|:---|:---|
| 定位 | 阿里电商规则表达式引擎，Java 原生 | GraalVM JavaScript 实现，Polyglot 运行时 |
| 语法 | Java 风格（三元、列表、Map、方法调用） | 真 JS / ES6+（箭头函数、解构、模板字符串） |
| 依赖体积 | ~250KB | 数 MB（polyglot 全家桶） |
| OpenJDK 21 性能 | 解释+编译缓存，微秒级 | **只能解释执行**，官方原话"significantly worse" |
| 安全模型 | 原生操作符白名单、禁函数调用 | 须手搓 HostAccess 沙箱，配置面大易留洞 |
| 线程模型 | ExpressRunner 线程安全可单例 | Context 不能跨线程须池化 |
| License | Apache-2.0（私有化交付友好） | GPL2+CE（License 审查敏感） |
| 中文资料 | 丰富 | 以英文为主 |
| 项目契合度 | ★★★★★ Java 同构、零依赖 | ★★ 与纯 Java 栈割裂 |

### 1.3 GraalJS 翻案条件（调研结论：当前不值，但记录翻案场景）

- 客户/业务方明确要求 JS 语法
- 表达式需要完整 JS 能力（正则、数组方法链、JSON.parse）
- 运行环境统一是 GraalVM JDK
- 即使满足以上，仍需权衡 GPL2+CE 协议、polyglot 依赖、沙箱配置复杂度

### 1.4 项目当前策略（与 roadmap §二 一致）

分三步，**现在只做第 1 步，连 QL 都不引**：
1. **现在**：冻结 `{{ }}` 标记，求值仍走 jayway（零新增依赖、零引擎引入）
2. **触发信号后**：引 QLExpress4，落点 `DatabusContext.resolve`，路径仍由 jayway 解析
3. **之后**：前端自动补全 + 求值结果预览

**触发信号**（沿用 2026-09-20 调研定义，至今未出现）：
- 旧 meta 公式频度摸底出现高频一行计算
- 实测中"为一行三元表达式拖一个 Groovy 节点"的摩擦被用户明确抱怨

---

## 二、QL 的能力边界：能做什么、不能做什么

### 2.1 QL 能做的（表达式级）

- 全算术/比较/逻辑：`+ - * / % > < >= <= == != && || !`
- 三元：`a > b ? x : y`
- 列表/Map 字面量：`[1,2,3]`、`{name:'张三'}`
- 成员访问与方法调用：`$.user.getName()`、`$.list.size()`
- 自定义函数注入：`jsonpath('$..records[?(@.age>18)]')`
- 多语句 + 变量赋值 + return
- 宏定义

### 2.2 QL 做不到的（即使不限制操作符）

- **没有原生 for/while 循环**——表达式语言与脚本语言的根本分界
- 没有 try/catch 异常处理
- 没有 if/else if/else 多分支（只有三元）
- 不能在表达式内定义函数/类
- 不能跨迭代的状态累积

### 2.3 能力层级关系（关键认知）

```
第 1 层  结构化组件（表单，零语法）        ← 已有 15+ 组件
第 2 层  参数槽内联表达式（QL）            ← 缺口，QL 的定位
第 3 层  代码节点（Groovy 脚本）          ← 已有，能力天花板
```

**第 2 层是第 3 层的能力子集**。QL 不是能力补充，是**摩擦缩减**——只为省掉"为一行表达式拖一个脚本节点"的画布噪音。脚本节点能替代 QL 的一切，但替代不了 QL 的"零摩擦"。

---

## 三、QL 引入后 jayway 与 QL 的职责划分

### 3.1 现在（没有 QL）

```
{{ $.amount }}                    ✅ jayway 能做（纯路径取值）
{{ $.amount <= 1000 }}            ❌ jayway 做不了（有 <= 比较）
{{ $.price * 0.8 }}               ❌ jayway 做不了（有乘法）
{{ $.a == 'x' && $.b == 'y' }}   ❌ jayway 做不了（有逻辑运算）
```

jayway 是 JSONPath 库，**只取值，不认算术/比较/逻辑运算**。要做这些必须拖 Groovy 脚本节点。

### 3.2 QL 之后

```
{{ $.amount }}                    ✅ QL 调 jsonpath() 函数取值
{{ $.amount <= 1000 }}            ✅ QL 先取值再比较，返回布尔
{{ $.price * 0.8 }}               ✅ QL 先取值再算术
{{ $.a == 'x' && $.b == 'y' }}   ✅ QL 先取值再逻辑组合
```

**职责分工**：QL 负责表达式运算（比较/算术/逻辑/三元），路径解析仍委托 jayway（通过注入 `jsonpath()` 自定义函数）。

### 3.3 画布对比（同一个"金额判断走分支"场景）

**现在（没有 QL）**——要拖脚本节点写判断：
```
[FOR]
  └─ [Groovy 脚本节点]              ← 多一个节点 + 两根线
       def i = ctx.getLoopVar('$i')
       def amount = ctx.read('$.expenses[' + i + '].amount')
       return amount <= 1000
  └─ [IF] 条件: 脚本节点返回值
       ├─ 真: [setValue] $.results[$i] = "PASS"
       └─ 假: [httpRequest] 调审批
```

**QL 之后**——IF 条件直接写表达式：
```
[FOR]
  └─ [IF] 条件: {{ $.expenses[$i].amount <= 1000 }}
       ├─ 真: [setValue] $.results[$i] = "PASS"
       └─ 假: [httpRequest] 调审批
```

少一个节点、少两根连线——**这就是 QL 带来的唯一实际变化**。

---

## 四、循环变量 $i / $j / $k 机制详解

### 4.1 本质

`$i/$j/$k` 不是 QL 的特性，也不是表达式引擎的能力。它是**循环组件（FOR/ITERATOR）和参数槽表达式之间的桥梁**——让循环体内的可视化节点能引用"当前迭代上下文"，同时不破坏并行隔离。

### 4.2 FOR vs ITERATOR 的 $i 语义差异

| 循环类型 | `$i` 的含义 | 适用场景 |
|:---|:---|:---|
| **FOR** | 数字索引（0, 1, 2...） | 需要按位置回写、需要序号、需要前后元素对比 |
| **ITERATOR** | 当前元素本身 | 只需逐个处理元素，不需要知道是第几个 |
| 嵌套 | 第 1 层 `$i`、第 2 层 `$j`、第 3 层 `$k` | 上限 3 层 |

### 4.3 真实应用示例：批量审核报销单

**输入**：
```json
{
  "expenses": [
    { "id": "E001", "amount": 500,  "applicant": "张三" },
    { "id": "E002", "amount": 5000, "applicant": "李四" },
    { "id": "E003", "amount": 1200, "applicant": "王五" }
  ]
}
```

**画布**：
```
[开始] → [FOR i=0..2] → ┌─ [IF] {{ $.expenses[$i].amount <= 1000 }}
                          ├─ 真: [setValue] $.auditResults[$i] = "AUTO_PASS"
                          └─ 假: [httpRequest] body={{ $.expenses[$i] }}
                                 → [setValue] $.auditResults[$i] = {{ $.httpResp.data.result }}
                         └─ → [结束]
```

**$i 出现在三个地方，各有作用**：
| 位置 | 表达式 | 作用 |
|:---|:---|:---|
| IF 条件 | `{{ $.expenses[$i].amount <= 1000 }}` | 取元素：用索引定位当前报销单 |
| httpRequest body | `{{ $.expenses[$i] }}` | 取元素：把当前整条报销单发出 |
| setValue 路径 | `$.auditResults[$i]` | 回写对齐：结果写到结果数组同一位置 |

**执行过程**：
| 迭代 | $i | 取到 | 分支 | 写入 |
|:---|:---|:---|:---|:---|
| 1 | 0 | amount=500 | 真 | `auditResults[0] = "AUTO_PASS"` |
| 2 | 1 | amount=5000 | 假→调接口 | `auditResults[1] = "NEED_MANAGER"` |
| 3 | 2 | amount=1200 | 假→调接口 | `auditResults[2] = "NEED_MANAGER"` |

### 4.4 $i 的不可替代性

如果没有 `$i`，实现"遍历逐个处理 + 结果按位置回写"只有三条路，都有硬伤：

- ❌ 写死 N 个节点：不通用，数量一变就废
- ❌ 把索引写进数据空间 `$.loop.i`：WHEN 并行下多循环互相覆盖（正是并发红线的根因）
- ❌ 整个循环塞进 Groovy 脚本节点：失去可视化，循环体内不能再拖其他组件

> `$i` 的设计本质：让循环体内可视化节点引用当前迭代上下文，同时不碰共享数据空间、不破坏并行隔离。只要还在用"可视化循环组件 + 循环体内嵌组件编排"这个模式，`$i` 不可替代。

---

## 五、循环变量注册与识别机制（当前已落地实现）

### 5.1 整体架构

| 层面 | 谁负责 | 做什么 |
|:---|:---|:---|
| **注册/注销** | 循环组件（FOR/ITERATOR） | 每次迭代 push/pop ThreadLocal 栈 |
| **识别** | PathResolver（所有节点共用） | 解析参数时从栈取 $i 做替换 |
| **隔离** | ThreadLocal | 并行循环各自独立 |
| **跨节点传递** | reconcile 钩子 | LiteFlow 子链执行前同步变量栈 |

### 5.2 ThreadLocal 栈结构（DatabusContext 内部）

```java
ThreadLocal<Deque<Map<String, Object>>> loopVarStack;    // 变量栈（核心）
ThreadLocal<Map<String, Object>> pendingLoopVars;        // 待绑定（跨钩子传递）
ThreadLocal<Map<String, Integer>> loopIndexMap;          // 层级→数字索引
```

### 5.3 FOR 组件注册过程

```java
public class ForLoopComponent extends NodeComponent {
    @Override
    public void process() {
        DatabusContext ctx = getContextBean(DatabusContext.class);
        for (int idx = from; idx <= to; idx += step) {
            ctx.pushLoopVar("$i", idx);     // ① 注册：$i = 数字索引
            // ② LiteFlow 执行循环体内子节点
            //    子节点参数解析触发 PathResolver → 从栈取 $i
            ctx.popLoopVar("$i");           // ③ 注销
        }
    }
}
```

### 5.4 ITERATOR 组件注册过程

```java
public class IteratorLoopComponent extends NodeComponent {
    @Override
    public void process() {
        DatabusContext ctx = getContextBean(DatabusContext.class);
        List<?> items = (List<?>) ctx.read(cfg.getCollectionPath());
        for (Object item : items) {
            ctx.pushLoopVar("$i", item);    // ① 注册：$i = 元素本身（非索引）
            // ② LiteFlow 执行子节点
            ctx.popLoopVar("$i");           // ③ 注销
        }
    }
}
```

### 5.5 嵌套循环栈状态

```
外层 ITERATOR 第 1 次迭代:  loopVarStack = [{$i: "u001"}]
  内层 ITERATOR 第 1 次迭代: loopVarStack = [{$i: "u001"}, {$j: "o1"}]
  内层结束:                  loopVarStack = [{$i: "u001"}]
外层结束:                    loopVarStack = []
```

### 5.6 识别：substituteLoopVars 字符串词法替换（当前实现）

```java
public Object resolveParam(String raw, DatabusContext ctx) {
    if (!raw.contains("{{")) return raw;                    // 纯字面量
    Map<String, Object> loopVars = ctx.getLoopVars();       // {$i: 0, $j: "o1"}
    String resolved = raw;
    for (Map.Entry<String, Object> entry : loopVars.entrySet()) {
        // 词边界正则 \$i\b 防止 $index 被误伤
        resolved = resolved.replaceAll(
            Pattern.quote(varName) + "\\b",
            Matcher.quoteReplacement(varValue.toString())
        );
    }
    return jaywayEvaluate(resolved);
}
```

替换示例：
| 节点 | 原文 | $i=0 替换后 | jayway 求值 |
|:---|:---|:---|:---|
| httpRequest | `{{ $i }}` | `{{ 0 }}` | `0` |
| IF 条件 | `{{ $.expenses[$i].amount }}` | `{{ $.expenses[0].amount }}` | `500` |
| setValue 路径 | `$.auditResults[$i]` | `$.auditResults[0]` | 写入数组第 0 位 |

### 5.7 为什么用 ThreadLocal 而不是全局 Map

```
WHEN 并行：
  分支 A（线程-1）: FOR → loopVarStack(线程-1) = [{$i: 0}]  ← 互不干扰 ✓
  分支 B（线程-2）: FOR → loopVarStack(线程-2) = [{$i: 0}]

如果用全局 Map（$.loop.i 写进数据空间）：
  分支 A 写 $.loop.i = 0
  分支 B 写 $.loop.i = 0  ← 覆盖或 ConcurrentModificationException
```

ThreadLocal 栈天然隔离并行线程，不碰共享数据空间，与 roadmap §三 WHEN 并发红线的修复方向一致。

---

## 六、QL 引入后循环变量的变化（仅"识别"环节变化）

### 6.1 变与不变

| 机制 | 现在 | QL 之后 |
|:---|:---|:---|
| 注册/注销 | push/pop ThreadLocal 栈 | **不变** |
| 隔离 | ThreadLocal | **不变** |
| 跨节点传递 | reconcile 钩子 | **不变** |
| **识别** | substituteLoopVars 字符串替换 | 注入 QL 求值作用域真变量 |

### 6.2 识别环节的前后对比

**现在（字符串替换）**：
```
原文: {{ $.expenses[$i].amount }}
  ↓ substituteLoopVars 把 $i 替换成 0
替换后: {{ $.expenses[0].amount }}
  ↓ jayway 求值（只取值，不认算术/比较/逻辑）
结果: 500
```

**QL 之后（真变量注入）**：
```
原文: {{ $.expenses[$i].amount <= 1000 }}
  ↓ QL 求值时 $i 是作用域里的真 int 变量（=0）
  ↓ jsonpath() 函数读 $.expenses[0].amount → 500
  ↓ QL 做比较 500 <= 1000
结果: true（布尔值，IF 节点直接用）
```

### 6.3 QL 给循环变量带来的三个实际好处

1. **`{{ $i + 1 }}` 能算了**——现在字符串替换变成 `0+1` 交给 jayway，jayway 不认算术
2. **写错变量名解析期就报错**——现在 `$x` 写错了静默不替换，运行时才炸
3. **$i 不再被字符串字面量误伤**——现在词边界正则偶尔误替换

---

## 七、关键认知收束

1. **QL 是便利（可等触发），$i 是刚需（已有）**：QL 是摩擦缩减层，真正的能力天花板是 Groovy 脚本节点；$i 是可视化循环组件模式的刚需，与 QL 无关。
2. **注册/隔离/传递三件已做好，QL 引不引都不变**：QL 只改最后一步"怎么把 $i 喂给求值器"。
3. **当前策略是语法先行、引擎后置**：`{{ }}` 标记已冻结，求值仍走 jayway；QL 等触发信号才引。
4. **GraalJS 明确否决**：为 JS 语法手感引入整套 polyglot 运行时不划算。

---

## 附：与既有 wiki 文档的对应关系

| 本文章节 | 对应既有 wiki | 关系 |
|:---|:---|:---|
| §一~§二 | `databus-formula-engine-research.md` | 补充 QL vs GraalJS 的详细对比论证 |
| §三 | `databus-expression-syntax.md` §1-§2 | 补充 QL 引入前后 jayway 职责变化的具体示例 |
| §四~§五 | `databus-loop-component.md` | 补充 $i 注册/识别机制的端到端流程拆解 |
| §六 | `databus-expression-syntax.md` + `databus-loop-component.md` | 补充 QL 引入对循环变量识别环节的变更说明 |
