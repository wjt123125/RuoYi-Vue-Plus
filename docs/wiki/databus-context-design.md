# DatabusContext 数据总线执行上下文设计

> 模块：`ruoyi-databus` (`org.dromara.databus.context`)
> 关键类：`DatabusContext` / `PathResolver` / `JsonCodec`
> 原系统对照：`com.awspaas.user.apps.data.bus.document.proxy.IndexAwareReader`（动态变量代理）

## 1. 这个模块解决什么问题

数据总线的链路执行需要一块"上下文"在节点间流转数据。原系统基于 AWS BPM 的 `OperationContext`，强耦合 `userContext` / `processInstance` / `taskInstance` 等 BPM 运行时对象。本模块去除 BPM 依赖，只保留数据总线自身的三件事：

1. **JSONPath 读写**：节点通过 `$.user.name` 这类路径读写上下文，是节点间数据流转的基础
2. **`{{ }}` 动态标记解析**：参数里写 `{{ $.xxx }}` 引用上下文，如 `用户{{ $.user.name }}`，运行时求值替换——这是数据总线参数绑定的特有需求，LiteFlow 原生 `getContextValue`（基于 POJO 反射）不支持动态 JSON 文档
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
│  │  PathResolver   {{}} 表达式识别 + 片段正则替换            │ │
│  │  JsonCodec      Jackson 序列化（片段替换时用）            │ │
│  └─────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

`DatabusContext` 是薄封装，路径读写直接走 jayway 原生 API，本类只负责：
- `{{ }}` 表达式识别与求值（整字段 / 嵌入片段 / `$i` 循环下标）
- 自动建路径写入（父路径不存在时逐层建 Map / 按索引扩容数组）
- 统一参数解析入口 `resolve(Object)`

## 3. 核心概念

### 3.1 字段角色与 `resolve()` 分发

**写法不由填写人临时判断，而由字段角色决定**：

- **「要数据」字段**（组件拿它为得到内容，如 condition.path、iterator.source、response.dataPath、fieldMap.from、HTTP mappings.path、各 sourcePath/boId）：动态值一律写 `{{ $.路径 }}`，组件调 `resolve()`，不再直接 `read()`
- **「起名字」字段**（写入位置，如 setValue.path、fieldMap.to、dataPatch.target、rewrite.path）：写**裸路径**，不被求值
- **普通字面量**（`3`、`"POST"`、`"admin"`）：原样使用

外观三种（字面量 / 裸路径位置名 / `{{ }}` 表达式），处理路径只有两条（原样 / 求值）。

`DatabusContext.resolve(Object input)` 是节点取参的统一入口：

| 输入形态 | 例子 | 处理 |
| --- | --- | --- |
| 整字段表达式 | `{{ $.user.name }}` | `read(path)` 取值，**保留原类型** |
| 嵌入表达式 | `用户{{ $.user.name }}` | `resolveEmbedded` 替换 `{{ }}` 片段后拼接 |
| 含循环下标 | `{{ $.items[$i].id }}` | `$i` 先替换为当前轮索引，再 `read` |
| 无标记（字面量 / 裸路径位置名） | `123` / `$.target.name` | **原样返回** |

判断逻辑见 `PathResolver`：`wholeExpression` / `containsExpression`，以及 `DatabusContext` 的 `$i` 直接下标替换。

### 3.2 嵌入表达式解析

模板 `用户{{ $.user.name }}, 年龄 {{ $.user.age }}` → `resolveEmbedded` 用非贪婪正则 `EXPRESSION_PATTERN` 匹配所有 `{{ ... }}` 片段，逐个从 `DocumentContext.read` 取值并按下列规则替换：
- `null` → `"null"`
- 字符串 → 原样拼接
- 数值 / 布尔 → `toString`
- 对象 / 数组 → JSON 序列化
- **片段取值失败（路径不存在等）→ 打 WARN 日志（无 log_level 门控、不拦执行），原片段保留**
- 整字段表达式（字段值恰为一个 `{{ }}`）路径不存在 → 直接抛错（显式引用须响，不做静默）

### 3.3 自动建路径写入

`write(path, value)` 父路径不存在时 `createPath` 递归创建：对象逐层建 `LinkedHashMap`，数组按索引扩容并补 `LinkedHashMap` 占位。提炼自原系统 `DocumentUtil.createPath`，去除 AWS SDK 依赖。

### 3.4 链路入参登记表（2026-09-27 拍板）

**背景**：业界（Airflow Params、n8n Variables、GitHub Actions inputs）把值按作用域分层——每次跑会变的登记为入参（带默认值）、大家共用的由管理员存为公共常量、随环境走的放环境配置、密钥单独加密。我们此前三层皆无，试运行入参是自由 JSON，链路需要什么全靠人脑记。

**本次只做「链路入参」一层**；公共常量 / 环境配置等出现真实触发信号再做。

- **存储**：`databus_chain` 加字段（如 `input_params`）存登记 JSON，跟链路走，天然按链路隔离
- **登记条目四项**：路径（输入框直接填写完整路径，以 `$.` 开头，如 `$.request.password`；路径不可重复）、类型（文本/数字/布尔/对象/数组，仅存标记，本次不做类型校验）、默认值、必填
- **左右双栏实时联动**：弹窗左侧条目表格、右侧 JSON，两边都能编辑——左改右即时拼 JSON，右改左即时按叶子拆条目（路径自动带、类型按值推断）；JSON 语法错时右侧提示、左侧保留，左侧有坏行（路径非法 / 默认值 JSON 写坏）时左侧提示、右侧保留上次合法 JSON；必填标记 JSON 中无对应，同步时按路径保留、新条目默认不勾
- **试运行预填**：每次打开试运行按登记表默认值重新生成 JSON（重开重置、「再跑一次」保留当前）；无默认值的条目跳过不塞 null；非文本默认值尝试 JSON.parse
- **必填校验**：试运行与外部 API 真实执行都做，在最终 JSON 上按必填路径取值，取不到 / 空字符串即拦截；默认值不注入真实执行（默认值只服务人工试运行）
- 链路引用写法、`{{ }}` 规则、外部调用方传 JSON 方式均不变

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

**实际落地（2026-09 循环组件交付时）未接代理层，改用更轻的「直接下标替换」**：

- 循环组件（`ForLoop` / `IteratorLoop`，经 `LoopSupport`）进入时把本层下标名（默认 `$i/$j/$k`，可 `indexVar` 自定义）注册到 `DatabusContext`
- `resolve()` 内先对 `$i` 等已注册占位符做字符串替换，再按整字段/嵌入表达式求值；裸串 `"$i"` 直接返回当前下标 Integer
- 嵌套循环的层级隔离由「按深度分配默认名 + 进入注册/退出清理」保证，不需要 `indexMap` 快照
- 旧代理设计（IndexAwareReader）保留为参考实现；如未来出现更复杂的变量注册需求再评估接入

## 5. 关键代码位置索引

| 文件 | 作用 |
| --- | --- |
| `org.dromara.databus.context.DatabusContext` | 上下文对外 API，持 jayway `DocumentContext` |
| `org.dromara.databus.context.PathResolver` | `{{ }}` 表达式识别（整字段/嵌入）+ 片段正则替换 |
| `org.dromara.databus.context.JsonCodec` | Jackson 序列化（片段替换时用） |
| `org.dromara.databus.component.DatabusNodeComponent` | 组件基类，封装 `get` / `save` / `resolveParam` |
| `org.dromara.databus.executor.DatabusExecutor` | 执行器入口，`execute2Resp` 时把 `DatabusContext` 绑定到 LiteFlow slot |
| 原系统 `IndexAwareReader` | 动态变量代理参考实现（待迁移） |

## 6. 常见坑

1. **`DatabusExecutor` 把 `requestData` 同时传给 `flowExecutor.execute2Resp` 的 slotParam 和 `DatabusContext.fromObject`**——两个通道有点重复，1C 闭环时该细化（slotParam 与 context 的职责分工）
2. **`$i` 替换只在 `resolve()` 路径生效**：直接调 `read("$.items[$i].name")` 不会替换下标。循环体内取数必须把字段配成 `{{ $.items[$i].name }}` 走 resolve；组件代码则应先取循环 index bean 自行拼路径
3. **无标记字符串一律原样**：`resolve("$.user.name")` 返回的是字符串 `"$.user.name"` 而不会读值——动态引用必须包 `{{ }}`；这也保证 setValue.path 等「起名字」裸路径不被误求值
4. **WHEN 并行必须走锁，禁止直接暴露内部文档**：jayway 底层是普通 `LinkedHashMap`/`ArrayList`，WHEN 多分支并发写同一棵树会丢键/结构损坏，遍历快照还可能拍到半构建的树。`DatabusContext` 已内置 `ReentrantReadWriteLock`（读方法取读锁、`write/save/registerConnection` 取写锁，`createPath` 只在写锁内调用）；新增读入口必须包读锁、新增写入口必须包写锁，**禁止在读锁内升级写锁**（不支持锁升级会永久阻塞）；裸 `DocumentContext` 后门 `getDocument()` 已删除，不得重新加回。完整分析见 [databus-context-concurrency.md](databus-context-concurrency.md)

## 7. 扩展指南

- **新增用户可配字段**：先判定字段角色——「要数据」一律走 `resolve()` 并在文档/前端提示写 `{{ $.路径 }}`；「起名字」（写目标）裸路径直接用于 `save/write`，不要经过 resolve
- **新增循环下标名**：由循环组件经 `LoopSupport.registerLoopVar` 注册，`resolve()` 的下标替换自动生效；组件内部自行拼路径时应从循环 index bean 取值，不依赖 resolve
- **嵌套循环**：默认下标名按深度分配（`$i/$j/$k`），层级隔离无需手动维护；自定义名需通过注册时的重名校验
