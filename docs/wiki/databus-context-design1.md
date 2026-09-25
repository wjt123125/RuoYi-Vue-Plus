# Databus 执行引擎上下文层设计（1B 骨架）

> 所属模块：`ruoyi-databus`，包路径 `org.dromara.databus.context` / `org.dromara.databus.component`
> 更新日期：2026-09-14
> 状态：设计已拍板，待实现

> ## ⚠️ 2026-09-16 修订（1C discuss 拍板，以本块为准，下文旧推导保留备查）
>
> 1C「试运行真执行」讨论发现本文档的前提（nodeId 链路内唯一）不成立：画布序列化采用官方多实例形态 `THEN(httpRequest.tag("httpRequest1"), httpRequest.tag("httpRequest2"))`，**EL 里的 nodeId 是组件注册名，同一条链允许重复**（官方文档原文示例 `THEN(a.tag("1"), a.tag("2"), a.tag("3"))`；已 javap 核验 liteflow-core 2.16.0 的 Node 上 nodeId/tag 为两个独立字段，框架无唯一校验）。修订结论：
>
> | 原结论（2026-09-14，下文） | 现行结论（2026-09-16 起） |
> | --- | --- |
> | nodeId 链路内唯一，用作产出隔离键 | nodeId = 组件注册名（httpRequest/condition/…），**可重复**，不作隔离键 |
> | tag 语义是标签，不用 tag | **tag = 数据空间名（dataSpace）**，画布生成、强制唯一、用户可改语义短名；默认按同类型序号（httpRequest1） |
> | 节点产出挂 `$.nodes.{nodeId}` | 节点产出挂 **`$.<dataSpace>.xxx`**（即 tag 名，无 nodes 中间层），如 `$.httpRequest1.response`；response 组件写标准出口 `$.response.result/msg/data` |
> | 入参走 requestData 通道不进文档，需"取参组件"快照 | **入参即上下文文档根**：执行入口用 `DatabusContext.fromObject(requestData)`（DatabusExecutor 现状即如此），不设取参组件；requestData 参数同时照传 |
> | 基类 afterProcess 统一写 `$.nodes.{nodeId}` | 基类不做自动写入；组件按自身 data 规格自行 `save()`（DatabusNodeComponent 现状即此） |
> | 无 `${}` 花括号语法，需移除 | 新配置**只用裸路径**；PathResolver 的花括号兼容分支**保留不删**，仅供 1D 迁移旧系统配置 |
>
> 下游配套：画布改名 dataSpace 时按 JSONPath 路径段精确联动改写全树引用。完整决策见 plus-ui `.trae/handoff/preview-run-loop.md`（v2）§2。

## 1. 这个文档解决什么问题

1B 阶段要交付执行引擎骨架四件套：`DatabusContext`（上下文）、`DatabusNodeComponent`（组件基类）、`DatabusExecutor`（执行入口）、节点执行拦截器。骨架的目标是后续迁移 20+ 业务组件时开箱即用，不用回头改地基。

本文档是上下文层的设计决策全集，回答以下问题：

- DatabusContext 的 JSON 文档顶层结构长什么样？
- 入参、节点产出、元信息分别放哪里？
- 参数配置项（name + value + dataType）如何解析、值从哪来？
- WHEN 并行场景下如何保证线程安全？
- nodeId 作为路径段有什么约束？

## 2. 核心设计决策一览

| 决策项 | 结论 |
| --- | --- |
| 文档顶层结构 | 只有 `$.nodes.{nodeId}` 一个区 |
| 入参（requestData） | 走 LiteFlow 官方 `requestData` 通道，**不直接进文档**；由业务侧的"取参组件"读 requestData 后写入 `$.nodes.{取参组件nodeId}` |
| 节点产出 | 统一挂 `$.nodes.{nodeId}`，nodeId 天然替代老系统的 namespace |
| meta（executionId 等） | 第一版**不进文档**，类结构留扩展位 |
| global 共享区 | 第一版**不开放**，类结构留扩展位 |
| namespace 用什么 | **用 nodeId，不用 tag**（tag 语义是标签非身份，且运行时可变） |
| 参数解析语法 | **裸 `$.path` 嵌入字符串做片段替换，无 `${}` 花括号语法**（老系统没有花括号设计，是历史臆测，需移除） |
| 公式（@公式） | 骨架阶段识别后原样返回，不执行，推迟到后续阶段 |
| 类型转换 | 归入 1B 全做（convertType + JsonPathTypeParser），JSON 库用 Jackson |
| 线程安全 | DatabusContext 包 `ReentrantReadWriteLock`，所有 read/write 走锁 |
| 写入时机 | 节点 process() 返回 result，由基类 afterProcess 统一写入 `$.nodes.{nodeId}` |
| 多上下文 | **不用**，单 DatabusContext 即可 |
| 配置项 | 不进 context，是节点元数据，挂节点属性 |

## 3. DatabusContext 文档结构

### 3.1 第一版结构

```json
{
  "nodes": {
    "取参组件nodeId": { ... },
    "nodeA": { ... },
    "nodeB": { ... }
  }
}
```

**只有 `$.nodes.{nodeId}` 一个顶层区。**

### 3.2 为什么只有 nodes 一个区

老系统用 namespace 字段做隔离，每个操作的参数挂在 `$.{NAMESPACE}` 下。新系统用 nodeId 替代 namespace：

- nodeId 是 LiteFlow 框架的唯一标识（EL 里 `THEN(a, b)` 的 a/b 就是 nodeId）
- nodeId 注册后定死，运行时不可变
- 编辑器里每个节点有唯一 nodeId，天然隔离各节点的产出区

废弃了老系统的 `groupPath` 中间层——它在老系统里没有实际作用，只增加复杂性。参数配置只需 name + value + dataType。

### 3.3 扩展位（第一版不开放）

DatabusContext 的类结构上预留 `$.meta` 和 `$.global` 两个顶层 key 的扩展能力，但第一版不写入、不读取。

- `$.meta`：执行元信息（executionId、时间戳等），第一版不进文档，由 `DatabusExecutionResult` 承载
- `$.global`：跨节点共享但不属于任何节点的数据（如全局计数器），第一版没有明确场景，不开放。如果后续需要，必须约定写入规则（如只有特定组件能写），避免变成垃圾桶

## 4. 数据流转

### 4.1 完整链路

```
外部入参（HTTP body / MQ 消息 / 定时触发参数）
    │
    ▼
LiteFlow requestData 通道（只读不动，官方推荐）
    │
    ▼
取参组件（普通业务组件，nodeId 由用户定义，如 requestNode）
    │  this.getRequestData() → 读入参
    │  save("$.nodes.{取参组件nodeId}", snapshot) → 写入文档
    ▼
业务组件 A（nodeId=nodeA）
    │  get("$.nodes.{取参组件nodeId}.xxx") → 读入参
    │  get("$.nodes.上游nodeId.xxx") → 读上游产出
    │  执行业务逻辑
    │  return result → 基类 afterProcess 写入 $.nodes.nodeA
    ▼
业务组件 B → ...
    │
    ▼
链路结束
response.getContextBean(DatabusContext.class) → 取整个文档
```

### 4.2 取参组件

取参组件是一个**普通业务组件**（不是骨架内置的特殊组件），由业务侧按需实现，nodeId 由用户自己定义（如 `requestNode`、`inputNode` 等）。它的作用：

1. 读取 `this.getRequestData()` 获取外部入参
2. 将入参快照写入 `$.nodes.{取参组件nodeId}`
3. 可做裁剪：只把需要的字段搬进文档，不必暴露整个 request body

取参组件放在链路的什么位置由编排决定，通常放在最前面：`THEN(requestNode, nodeA, nodeB, ...)`。

### 4.3 为什么不用多上下文

LiteFlow 支持传入多个 Context，但本场景不用：

- 官方文档说明多上下文是为"通用组件复用到不同流程、各流程上下文结构不同"准备的
- 我们所有节点共享同一个 DatabusContext 文档，数据通过 `$.nodes.{nodeId}` 隔离
- 取参组件方案已优雅解决入参访问问题，不需要第二个 Context

## 5. 什么进 context，什么不进

| 数据类型 | 是否进 context | 通道 |
| --- | --- | --- |
| 流程入参（外部传入） | ❌ 不进 | `this.getRequestData()` |
| 入参快照 | ✅ 进 `$.nodes.{取参组件nodeId}` | 取参组件写入 |
| 节点产出 | ✅ 进 `$.nodes.{nodeId}` | 基类 afterProcess 写入 |
| 节点配置项定义（name+value+dataType） | ❌ 不进 | 挂节点属性（NodeComponent 字段） |
| 配置项解析后的值 | ❌ 不进（局部变量） | pre 阶段解析后赋给组件属性 |
| executionId / 时间戳等 meta | ❌ 不进（第一版） | `DatabusExecutionResult` |
| 框架监控数据（耗时/错误） | ❌ 不进 | LiteFlow 自管（CmpStep） |

**核心原则：context 只放"组件产出的、需要被下游引用的数据"。**

## 6. 参数配置项与解析

### 6.1 配置项结构

每个节点的表单配置项是物料定义的产物，结构：

```
{ name, value, dataType }
```

- `name`：字段名
- `value`：值（可能是常量字符串，也可能是 JSONPath 表达式）
- `dataType`：目标类型（integer/float/double/boolean/object/xml/List<T>/T[]）

### 6.2 value 的两种形态

| 形态 | 例子 | 处理方式 |
| --- | --- | --- |
| 常量字面量 | `"alice"`、`18`、`true` | 直接使用 |
| JSONPath 引用 | `$.nodes.{取参组件nodeId}.userId`、`$.nodes.nodeA.result` | pre 阶段从 context 解析取值 |
| 混合路径字符串 | `"用户$.nodes.{取参组件nodeId}.name"` | pre 阶段替换 `$.xxx` 片段为实际值 |

### 6.3 解析时机：pre 阶段

在 `DatabusNodeComponent` 的生命周期中，参数解析在 **beforeProcess**（pre 阶段）完成：

1. 读取节点配置项的 value 字段
2. 调用 `resolveParam(value)` 解析：
   - 纯 JSONPath → `context.read(path)` 从文档取值
   - 混合路径 → `context.resolveMixedPath(template)` 替换片段
   - 常量 → 原样返回
3. 按 dataType 做类型转换（convertType）
4. 赋值给组件属性，供 process() 使用

**注意：解析的是配置项的 value 字符串，不是 requestData 本体。requestData 全程只读不动，符合官方"入参不可改"原则。**

### 6.4 老系统两处缺陷的修正

#### 缺陷一：resolveJsonPath 的 JSON 对象/数组递归分支是死代码

老系统用 `instanceof JSONObject/JSONArray`（fastjson 类型）判断，但 jayway 配了 JacksonJsonProvider，`read()` 返回的是 `LinkedHashMap`/`ArrayList`——这两个分支从来没跑通过。

**修正：** 改用 `instanceof Map/List` 判断。

#### 缺陷二：isPureJsonPath 正则缺 `[]`

老系统正则不支持数组索引，导致 `$.users[0]` 不被识别为纯路径，落入混合路径分支被字符串化（List 变成 JSON 字符串而非原对象）。

**修正：** 正则支持 `[` 和 `]`，如 `^\$\.[\w.$\[\]]+$`。

### 6.5 参数解析语法：只支持裸 `$.path`，不支持 `${}` 花括号

**目标设计：参数解析只支持裸 `$.path` 语法，不支持 `${$.path}` 花括号语法。**

- 老系统没有 `${}` 花括号设计，是历史臆测加进去的
- 现有 `PathResolver` 代码里同时支持 `${$.path}` 和裸 `$.path` 两种写法，**需要移除花括号那部分**
- `PATH_FRAGMENT_PATTERN` 只匹配裸 `$.xxx` 片段
- 混合路径替换逻辑对应简化

示例：
- 正确：`"用户$.nodes.{取参组件nodeId}.name"` → `"用户alice"`
- 错误（不支持）：`"用户${$.nodes.{取参组件nodeId}.name}"`

## 7. 类型转换体系

归入 1B 骨架全做，保证后续组件迁移开箱即用。

### 7.1 convertType

按参数的 dataType 做强类型转换，支持：

| dataType | 转换目标 |
| --- | --- |
| integer | Integer |
| float | Float |
| double | Double |
| boolean | Boolean |
| object | Map（递归处理内层公式与路径） |
| xml | 解析后递归处理 |
| List<T> | ArrayList |
| T[] | 数组 |
| 空值 | 默认值 |

### 7.2 JsonPathTypeParser

JSON 库用 **Jackson**（替代老系统的 fastjson），与 RuoYi-Vue-Plus 默认 JSON 库一致。

## 8. 线程安全

### 8.1 什么时候出问题

**WHEN 并行编排时。** LiteFlow 的 `WHEN(a, b, c)` 把 a/b/c 扔到线程池并发执行，三个线程共享同一个 DatabusContext 实例。

- jayway 的 `DocumentContext` 底层是 Jackson 的 `ObjectNode`/`ArrayNode`（或 `LinkedHashMap`/`ArrayList`），**不是线程安全的**
- 即使两个节点写不同路径（a 写 `$.nodes.a.xxx`，b 写 `$.nodes.b.yyy`），操作的是同一个根 `LinkedHashMap`，并发 `put` 不同 key 在 HashMap 扩容时会丢数据、甚至死循环
- a 写 `$.nodes.a` 的同时 b 读 `$.nodes.a`，可能读到半写状态

LiteFlow 官方明确：上下文线程安全由使用者负责，框架不管。

### 8.2 解决方案：ReentrantReadWriteLock

DatabusContext 内部包一把读写锁，所有读写操作走锁：

```
DatabusContext 内部：
├── jayway DocumentContext（实际存数据）
├── ReentrantReadWriteLock lock
├── read(path)          → lock.readLock().lock()
├── readOptional(path)  → lock.readLock().lock()
├── write(path, value)  → lock.writeLock().lock()
├── resolveMixedPath()  → lock.readLock().lock()
└── toJsonString()      → lock.readLock().lock()
```

为什么用读写锁而不是 synchronized：

- 大部分操作是读（解析 JSONPath 取值），读可以并发
- 只有节点产出时才写，写互斥
- 读多写少场景下读写锁性能更好

### 8.3 写入统一收口

不要让组件在 process() 中间随意写 context。约定：

- 组件 process() 返回 result 对象
- 基类 `afterProcess` 统一调用 `context.write("$.nodes." + nodeId, result)` 写入
- 写操作集中在一个地方，锁的粒度好控制

### 8.4 当前代码偏差（需修正）

现有 `DatabusContext` **没有任何锁**，`read`/`write`/`resolveMixedPath` 直接操作 jayway DocumentContext。实现时必须加锁。

## 9. nodeId 命名规范

### 9.1 为什么要约束

nodeId 同时承担两个角色：

1. LiteFlow EL 表达式里的节点标识（`THEN(a, b)` 里的 a/b）
2. JSONPath 路径段（`$.nodes.a.xxx` 里的 a）

如果 nodeId 含特殊字符（点号、空格、中文），JSONPath 会出问题；如果不符合 LiteFlow 节点命名规范，EL 解析会失败。

### 9.2 约束规则

编辑器生成 nodeId 时只允许：

```
[a-zA-Z][a-zA-Z0-9_]*
```

即：字母开头，后续字母/数字/下划线。

### 9.3 不用 tag 做 namespace 的原因

| 维度 | nodeId | tag |
| --- | --- | --- |
| 框架语义 | 唯一身份 | 标签/备注（辅助描述） |
| 运行时 | 注册后定死，不可变 | 有 setTag() 可改 |
| EL 引用 | 是，`THEN(a, b)` 直接用 | 否，EL 里不出现 |
| 适合做 namespace | ✅ | ❌（语义错位 + 可变） |

## 10. 与现有代码的偏差清单（实现时需修正）

| 文件 | 现状 | 目标 |
| --- | --- | --- |
| `DatabusExecutor.execute()` | `DatabusContext.fromObject(requestData)` 把入参直接塞进文档根 | 改为 `DatabusContext.empty()` 创建空文档，入参由取参组件搬进 `$.nodes.{取参组件nodeId}` |
| `DatabusContext` | 无锁 | 加 `ReentrantReadWriteLock`，所有 read/write 走锁 |
| `PathResolver` | 同时支持 `${$.path}` 花括号和裸 `$.path` | **只支持裸 `$.path`，移除花括号语法** |
| `DatabusContext.resolve()` | 已有纯路径/混合路径/动态变量三分支 | 保持，但修正 `isPureJsonPath` 正则支持 `[]` |
| `DatabusNodeComponent` | 只有 get/save/resolveParam | 增加 pre 阶段参数解析 + afterProcess 统一写入 `$.nodes.{nodeId}` |
| 取参组件 | 不存在 | 业务侧按需实现（普通组件，nodeId 自定义），读 getRequestData() 写 `$.nodes.{取参组件nodeId}` |
| 类型转换 | 不存在 | 新增 convertType + JsonPathTypeParser |

## 11. 关键类与职责

| 类 | 职责 | 包路径 |
| --- | --- | --- |
| `DatabusContext` | jayway DocumentContext 封装 + 读写锁 + 路径解析 | `org.dromara.databus.context` |
| `PathResolver` | 纯路径判断、混合路径替换、动态变量识别 | `org.dromara.databus.context` |
| `JsonCodec` | Jackson JSON 编解码 | `org.dromara.databus.context` |
| `DatabusNodeComponent` | 组件基类，pre 解析参数 + afterProcess 写产出 | `org.dromara.databus.component` |
| `DatabusExecutor` | 执行入口，生成 executionId、调用 LiteFlow、组装结果 | `org.dromara.databus.executor` |
| `DatabusNodeInterceptor` | 节点拦截器（日志埋点） | `org.dromara.databus.interceptor` |
| 取参组件（业务侧实现） | 读 requestData 写 `$.nodes.{取参组件nodeId}` | `org.dromara.databus.component` |

## 12. 常见疑问（FAQ）

**Q1：入参不进文档，混合路径解析还能用吗？**
能。取参组件把入参快照写进 `$.nodes.{取参组件nodeId}`，下游引用入参统一走 `$.nodes.{取参组件nodeId}.xxx`，和引用上游节点产出写法一致。

**Q2：LiteFlow 支持复杂 JSONPath 读取吗？**
不支持。LiteFlow 原生只支持强类型 Bean 的方法调用式存取（`getContextBean().getXxx()`）。JSONPath 是 Databus 自己引入的能力，全部自实现，框架不提供、不约束、不优化。

**Q3：参数解析时 value 从 JSONPath 变成了实际值，违反"入参不可改"吗？**
不违反。解析的是配置项的 value 字符串（节点元数据），不是 requestData 本体。requestData 全程只读不动。配置项的原始定义（JSONPath 字符串）也不被修改，只是"读取+解析"出一个新值给组件用。

**Q4：WHEN 并行时多个节点同时写文档怎么办？**
DatabusContext 的 `ReentrantReadWriteLock` 保证写互斥。即使各节点写不同 `$.nodes.{nodeId}` 路径，根 Map 是共享的，必须加锁。

**Q5：为什么不直接用 LiteFlow 的 DefaultContext？**
DefaultContext 是 Map 容器，存取数据要强转，弱类型。DatabusContext 用 jayway 支持完整 JSONPath 读写（嵌套对象、数组索引、过滤），是老系统的核心能力，迁 LiteFlow 必须保留。

**Q6：节点产出为什么由基类 afterProcess 统一写，而不是组件自己 save？**
统一收口的好处：① 写操作集中，锁的粒度好控制；② 路径 `$.nodes.{nodeId}` 由基类拼装，组件不用关心；③ 后续要加产出校验/审计只需改基类一处。

**Q7：混合路径语法到底支不支持 `${}` 花括号？**
**不支持。** 只支持裸 `$.path` 嵌入字符串，如 `"用户$.nodes.{取参组件nodeId}.name"`。`${$.path}` 花括号语法是历史臆测，老系统没有这个设计，现有代码里的花括号支持需要移除。

## 13. 扩展指南

### 13.1 新增一种 dataType

在 `convertType` 里加分支，在 `JsonPathTypeParser` 里加对应解析逻辑。

### 13.2 开放 $.global 区

1. 在 DatabusContext 里增加 `writeGlobal(key, value)` / `readGlobal(key)` 方法
2. 约定只有特定组件能写 global（如鉴权组件写 token）
3. 编辑器 Schema 校验时识别 `$.global.xxx` 路径

### 13.3 开放 $.meta 区

1. 在 DatabusContext 初始化时写入 executionId、startTime
2. DatabusExecutor 在 execute() 开始时创建 context 后写入 meta
3. 注意 meta 写入必须在节点执行前完成

## 14. 参考资料

- [LiteFlow 官方文档 - 如何理解上下文这个概念](https://liteflow.cc/pages/e1e61f/)
- [LiteFlow 官方文档 - 数据上下文的定义和使用](https://liteflow.cc/pages/501abf/)
- [LiteFlow 官方文档 - 普通组件](https://liteflow.cc/pages/8486fb/)
- [LiteFlow 官方文档 - 组件参数](https://liteflow.cc/pages/6e4d15/)
- 老系统 `OperationContext` / `BaseProcessor` / `JsonPathResolver` / `resolveJsonPath`
