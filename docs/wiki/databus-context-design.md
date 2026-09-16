# DatabusContext 数据总线执行上下文设计

> 模块：`ruoyi-databus` (`org.dromara.databus.context`)
> 关键类：`DatabusContext` / `PathResolver` / `JsonCodec`
> 原系统对照：`com.awspaas.user.apps.data.bus.document.proxy.IndexAwareReader`（动态变量代理）

## 1. 这个模块解决什么问题

数据总线的链路执行需要一块"上下文"在节点间流转数据。原系统基于 AWS BPM 的 `OperationContext`，强耦合 `userContext` / `processInstance` / `taskInstance` 等 BPM 运行时对象。本模块去除 BPM 依赖，只保留数据总线自身的三件事：

1. **JSONPath 读写**：节点通过 `$.user.name` 这类路径读写上下文，是节点间数据流转的基础
2. **混合路径解析**：参数模板里嵌入 `$.xxx` 片段，如 `用户${$.user.name}`，运行时替换为实际值——这是数据总线参数绑定的特有需求，LiteFlow 原生 `getContextValue`（基于 POJO 反射）不支持动态 JSON 文档
3. **动态变量**（循环场景）：路径里用 `[$i]` 作为数组下标占位符，循环执行时被替换为当前迭代索引

LiteFlow `getContextValue` 基于 POJO 反射，无法支持动态 JSON 文档与路径模板，所以必须自建上下文层。

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                      DatabusContext                          │
│  （对外 API：read / write / resolve / resolveMixedPath）      │
├─────────────────────────────────────────────────────────────┤
│  内部持 jayway DocumentContext（Jackson 作为 JSON 提供者）      │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  PathResolver   纯路径判断 + 混合路径正则替换              │ │
│  │  JsonCodec      Jackson 序列化（混合路径替换时用）         │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

`DatabusContext` 是薄封装，纯路径读写直接走 jayway 原生 API，本类只负责：
- 路径类型判断（纯路径 / 混合路径 / 动态变量 / 字面量）
- 自动建路径写入（父路径不存在时逐层建 Map / 按索引扩容数组）
- 统一参数解析入口 `resolve(Object)`

## 3. 核心概念

### 3.1 路径四种类型与 `resolve()` 分发

`DatabusContext.resolve(Object input)` 是节点取参的统一入口，按输入类型四档分发：

| 输入类型 | 例子 | 处理 |
| --- | --- | --- |
| 纯 JSON 路径 | `$.user.name` | 走 `read(path)` 从上下文取值 |
| 混合路径字符串 | `用户${$.user.name}` | 走 `resolveMixedPath` 替换 `$.xxx` 片段 |
| 含动态变量 | `$.items[$i].id` | **原样返回**，由循环组件延迟解析 |
| 字面量 | `123` / `"hello"` | 原样返回 |

判断逻辑见 `PathResolver`：`isPureJsonPath` / `isMixedPathString` / `containsDynamicVariable`。

### 3.2 混合路径解析

模板 `用户${$.user.name}, 年龄 ${$.user.age}` → 调用 `resolveMixedPath` → 用 `PATH_FRAGMENT_PATTERN` 正则匹配所有 `$.xxx` 片段，逐个从 `DocumentContext.read` 取值并替换。替换规则：
- `null` → `"null"`
- 字符串 → 转义双引号后拼接
- 数值 / 布尔 → `toString`
- 对象 / 数组 → JSON 序列化
- 路径不存在 → 保留原路径片段

### 3.3 自动建路径写入

`write(path, value)` 父路径不存在时 `createPath` 递归创建：对象逐层建 `LinkedHashMap`，数组按索引扩容并补 `LinkedHashMap` 占位。提炼自原系统 `DocumentUtil.createPath`，去除 AWS SDK 依赖。

## 4. 动态变量机制（循环场景，关键设计）

### 4.1 问题背景

JSONPath 是**静态路径**，不支持变量内插。`$.items[$.loopIndex].id` 里的 `$.loopIndex` 不会被 jayway 解析成数字——它会被当成字面路径。所以循环体内要引用"当前迭代索引"必须有某种**占位符 + 运行时替换**机制。

典型场景：遍历 `$.orders` 数组，每条订单取字段发请求，路径写 `$.orders[$i].orderId`，`$i` 在每次迭代被替换为 0/1/2。

### 4.2 原系统实现：IndexAwareReader 动态代理

原系统 `com.awspaas.user.apps.data.bus.document.proxy.IndexAwareReader` 用 JDK 动态代理包装 `DocumentContext`：

- 拦截 `read(String, Predicate[])` / `read(String, TypeRef)` / `set(JsonPath, Object)` / `set(String, Object, Predicate[])` 四个方法
- 维护 `indexMap: Map<String, String>`（占位符 → 实际值，如 `{"$i": "0"}`）
- 每次拦截调用前，对路径做 `String.replace` 把所有注册过的占位符替换为实际值
- 循环组件（如 `XmlLoopFragmentProcessor`）每次迭代前调 `updateIndex(indexMap)` 注册当前索引

**注册制避免误判**：只有循环组件主动 `updateIndex` 注册过的占位符（`$i`、`$j`、`$index0` 等）才会被替换，没注册的字符串（如 `Hello $name`）不会被动。这比纯正则判断更精确。

### 4.3 实际使用场景（原系统 grep 结果）

全部是 `[$i]` 作为数组下标占位符，没有别的用法：

| 场景 | 路径 | 说明 |
| --- | --- | --- |
| 单层循环取元素 | `$.INPUT.A[$i].F` | `$i` 替换为 0/1/2，读 A 数组第 i 项的 F |
| 嵌套循环 | `$.INPUT.A[i].B[$j].SUB_FIELD` | `$j` 内层索引（`i` 是字面量） |
| 命名占位符 | `$.data.items[$index0].name` | 占位符不限于 `$i`，可叫 `$index0` |
| 写回结果 | `$.result[$i].processed` | set 到结果数组第 i 项 |

### 4.4 替代方案评估：固定路径约定（方案 B）

评估过删代理改"固定路径约定"：循环组件每次迭代把当前元素写到 `$.current`，组件用纯 JSONPath `$.current.orderId` 读取，不引入占位符机制。

| 场景 | 动态变量 | 方案 B（固定路径） | 评价 |
| --- | --- | --- | --- |
| 单层循环取元素 | `$.orders[$i].orderId` | `$.current.orderId` | 都可，方案 B 更简单 |
| 嵌套循环 | `$.matrix[$i][$j]` | `$.outerCurrent` + `$.innerCurrent` | 都可，方案 B 路径命名要区分层级 |
| 写回当前元素 | `$.result[$i].processed=true` | `$.currentResult.processed=true`（靠 Map 引用语义反映回原数组） | 都可，依赖 jayway 引用语义 |
| **多平行数组同索引关联** | `$.orders[$i].x` + `$.discounts[$i].y` | 循环组件预取每个数组（路径命名爆炸）或重构数据模型合并平行数组 | **动态变量天然占优** |

### 4.5 决策结论（2026-09-16 拍板）

**保留 IndexAwareReader 代理机制，不删改方案 B。** 关键论据：

1. **多平行数组同索引关联**场景（`$.orders[$i].x` + `$.discounts[$i].y`），`$i` 是"索引值"可复用于任意数组，一次到位；方案 B 的 `$.current` 是元素引用只指一个数组，要么循环组件变臃肿（预取所有平行数组），要么强制求数据建模重构
2. 注册制已经规避了误判风险（没注册的占位符不动）
3. 原系统已有成熟实现，迁移成本低于重写

**但当前实现太粗犷，待 1C 循环组件落地时精细化**（已记入 work-state 待办）：

- 新系统 `DatabusContext` 尚未接入代理层，仍用 jayway 原生 `DocumentContext`——循环组件目前无法用动态变量
- `PathResolver.containsDynamicVariable` 用宽泛正则 `\$[a-zA-Z_]\w*` 判断，与代理层的注册制不一致（这是死代码分支，`resolve()` 遇到动态变量原样返回但无消费者）
- 精细化要做的事：① 把代理层接入 `DatabusContext`；② 循环组件约定 `updateIndex` 调用时机；③ 嵌套循环的多层 `indexMap` 隔离；④ 删 `PathResolver` 的死分支或改为代理的预热判断

## 5. 关键代码位置索引

| 文件 | 作用 |
| --- | --- |
| `org.dromara.databus.context.DatabusContext` | 上下文对外 API，持 jayway `DocumentContext` |
| `org.dromara.databus.context.PathResolver` | 路径类型判断 + 混合路径正则替换 |
| `org.dromara.databus.context.JsonCodec` | Jackson 序列化（混合路径替换时用） |
| `org.dromara.databus.component.DatabusNodeComponent` | 组件基类，封装 `get` / `save` / `resolveParam` |
| `org.dromara.databus.executor.DatabusExecutor` | 执行器入口，`execute2Resp` 时把 `DatabusContext` 绑定到 LiteFlow slot |
| 原系统 `IndexAwareReader` | 动态变量代理参考实现（待迁移） |

## 6. 常见坑

1. **`DatabusExecutor` 把 `requestData` 同时传给 `flowExecutor.execute2Resp` 的 slotParam 和 `DatabusContext.fromObject`**——两个通道有点重复，1C 闭环时该细化（slotParam 与 context 的职责分工）
2. **动态变量目前是死代码**：新系统 `resolve()` 遇到含动态变量的参数原样返回，但没有循环组件消费它。接入代理层前，循环场景跑不起来
3. **`PathResolver.containsDynamicVariable` 正则太宽**：`\$[a-zA-Z_]\w*` 会匹配 `Hello $name` 这种普通字符串，但因为代理层用注册制，实际不影响——只是 `resolve()` 的判断逻辑与代理机制不一致，接入代理层时要统一

## 7. 扩展指南

- **接入代理层**：在 `DatabusContext` 构造时用 `IndexAwareReader.createProxy()` 包装 `DocumentContext`，对外暴露 `updateIndex(Map<String, String>)` 给循环组件调用
- **循环组件约定**：FOR/WHILE/ITERATOR 组件在 `processIterator` / 循环 hook 里，每次迭代前 `indexMap.put("$i", String.valueOf(i))` + `updateIndex(indexMap)`，迭代后清理（避免污染外层）
- **嵌套循环**：外层 `indexMap` 与内层 `indexMap` 要隔离，内层进入时保存外层快照，内层退出时恢复——避免 `$i` 跨层级串值
