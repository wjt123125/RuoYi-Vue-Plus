# 循环组件（FOR/ITERATOR/WHILE）与 SWITCH 路由组件规格

> 适用版本：LiteFlow `2.16.1.3` · 模块：`ruoyi-databus` + plus-ui `views/databus/editor`
> 更新日期：2026-09-20

## 1. 解决什么问题

LiteFlow 提供 FOR / ITERATOR / WHILE 三种循环算子与 SWITCH 选择算子，但它们的条件位需要用户自己继承 `NodeForComponent` / `NodeIteratorComponent` / `NodeSwitchComponent` 写 Java 组件。数据总线在此前的画布上只能画结构，试运行不执行。

本档落地三个可配置的业务组件，并把循环当前轮下标以 `$i`（嵌套 `$j`/`$k`）形式注入全链路的路径解析：

| 算子 | 条件位组件（注册名） | LiteFlow 节点类型 | 配置 |
| --- | --- | --- | --- |
| FOR | `forLoop`（计数循环组件） | `NodeForComponent` | `count`、`indexVar` |
| ITERATOR | `iteratorLoop`（迭代循环组件） | `NodeIteratorComponent` | `source`、`indexVar` |
| SWITCH | `switchRoute`（选择路由组件） | `NodeSwitchComponent` | `source`、`cases` |
| WHILE | 不新增，复用布尔物料 `condition` / `booleanScript` | `NodeBooleanComponent` | 原有 cfg |

三个组件均在新包 `org.dromara.databus.component.flow`，`@LiteflowComponent` 自动扫描注册，**不需要** DB 种子行（物料清单以 plus-ui `cmp-defs.ts` 为准，与 script 等组件同策略）。

## 2. 组件契约

### 2.1 forLoop —— 计数循环

```json
{ "count": 3 }
{ "count": "$.request.total" }
{ "count": 3, "indexVar": "row" }
```

- `count`：整数常量（JSON number）、纯数字字符串、或 JSONPath（读出的值必须是数字）；路径中可用外层循环下标，如 `$.groups[$i].count`。
- `indexVar`：本层下标变量名（不含 `$`），留空按嵌套深度默认 `i/j/k`。
- 返回值即循环次数，LiteFlow 从 0 逐轮执行 DO；`0` 表示循环体零次执行；负数报业务异常。
- 步骤摘要（试运行结果列）：`共 N 次`。

### 2.2 iteratorLoop —— 迭代循环

```json
{ "source": "$.request.items" }
{ "source": "$.groups[$i].users", "indexVar": "u" }
```

- `source`：数组/集合路径，支持 `Collection` / `Iterable` / Java 数组；值为 `null` 按空集合处理（0 轮，不报错）；其他类型报配置错误。
- 路径中可用外层下标（嵌套迭代）。
- 步骤摘要：`共 N 项`（集合长度已知时）/ `开始迭代`。
- LiteFlow 限制：`NodeIteratorComponent` **不支持脚本节点**（引擎源码约定），迭代器只能由 Java 组件提供。

### 2.3 switchRoute —— 选择路由

```json
{
  "source": "$.request.type",
  "cases": [
    { "value": "A", "target": "caseA" },
    { "value": "$.request.vipCode", "target": "caseB" }
  ]
}
```

- 读出 `source` 实际值，按 `cases` 数组顺序逐条匹配，命中第一条即返回 `":target"`（冒号前缀走 LiteFlow 的 tag 匹配模式：`SwitchCondition` 对 targetList 中表达式按 `Executable.getTag()` 比对）。
- `value`：常量或路径（经 `DatabusContext.resolve` 解析，支持 `$i`）；两个数字按数值比较（`200` 与 `"200"` 视为相等，口径同 `ConditionComponent`）。
- `target`：画布上该分支的 case 名（SWITCH 算子 `outletLabels[i]`），为空报配置错误。
- **全部未命中即抛业务异常**，异常信息含当前值与候选值清单。本档不提供 DEFAULT 兜底（见 §6）。
- 步骤摘要：`命中分支 xxx`。

## 3. `$i` 循环索引机制

### 3.1 设计要点

不搬旧总线的 JDK 动态代理，索引完全由 `DatabusContext` 内的 ThreadLocal 栈维护，节点生命周期钩子驱动对账：

- `loopVarStack`：变量名栈（外 → 内），嵌套默认 `i/j/k`，上限 **3 层**；
- `loopIndexMap`：变量名 → 当前轮下标（Integer）；
- `pendingLoopVars`：循环控制组件注册的「待消费」自定义名，进第一层循环体时消费。

### 3.2 驱动时序（LiteFlow 2.16.1.3 源码实证）

1. `forLoop.processFor()` / `iteratorLoop.processIterator()` 开头调
   `LoopSupport.registerLoopVar`：此刻控制组件自身探测到的是**外层**深度
   （`LoopCondition.setLoopIndex` 经 `LiteflowMetaOperator.getNodes` 递归把外层下标压在 DO 内所有节点——含内层循环控制节点——自己的栈上），按「探测深度 + 1」把自定义名入待消费队列。
2. 全局钩子 `NodeStepResultCollector.postProcessBeforeNodeExecute` 对每个节点调
   `LoopSupport.syncBeforeNode`：
   - 循环控制节点执行前先 `clearPendingLoopVars()`——清掉上一个循环 0 轮执行留下的残留名；
   - `probeDepth`：`getLoopIndex() == null` → 深度 0；否则逐层 `getPreNLoopIndex(n)` 直到栈浅（Node 的 loopIndexTL 按下标条件 hashCode 分层，嵌套天然隔离）；
   - `reconcileLoopIndices(depth, 层下标取值器)`：进层消费 pending 名/补默认名，同层逐轮刷新下标，出层截断并重建 map；深度 0 清空。
3. 路径解析（`read` / `readOptional` / `write` / `exists` / `resolveMixedPath` / `resolve`）统一先做索引替换：
   - 裸 `$i`（词边界正则 `\$i(?![A-Za-z0-9_])`，长名优先）直接返回 `Integer`；
   - 路径中片段替换，如 `$.items[$i].name` → `$.items[0].name`；
   - 无 `$` 或 map 为空时零成本返回原串。
4. `DatabusExecutor` 两个执行入口（按 chainId / 按 EL 试运行）收尾必调 `context.clearLoopState()`，防工作线程复用串台。

### 3.3 自定义名校验

`pushPendingLoopVar(name, depth)` 在以下情况抛 `ServiceException`，且早于循环体执行：

- 深度超过 3 层；
- 名字不符合 `[A-Za-z_][A-Za-z0-9_]*`；
- 与外层已生效的变量名重名（同层两个循环不重叠，允许同名）。

空白名不入队，走按层默认。

## 4. EL 生成与分支 tag

### 4.1 条件位必须带 tag/data

修复前 FOR/ITERATOR/SWITCH 三个 parser 的 `generateCondition` 只拼裸 id，组件拿不到数据空间名与 cfg。现统一照 WHILE 的写法走 `appendNodeIdTagData`：

```
FOR(forLoop.tag("forLoop1").data("{...}")).DO(...)
ITERATOR(iteratorLoop.tag("iteratorLoop1").data("{...}")).DO(...)
SWITCH(switchRoute.tag("switchRoute1").data("{...}")).to(...)
```

`AbstractExpressParser.generateNodeComponent` 同时收敛为：凡 `id != null` 的节点（五种 LiteFlow 叶子类型）统一拼 `id.tag().data()`。

### 4.2 SWITCH 分支按 case 名挂 tag

SWITCH 算子的 `properties.outletLabels[i]` 即第 i 个 case 名，`SwitchConditionParser.generateCmp`：

- **单节点分支**：包一层 THEN 再挂 tag —— `THEN(setValue.tag("setValue1")).tag("caseA")`，
  避免 case 名污染节点自身的数据空间 tag；
- **子表达式分支**：tag 挂表达式末尾。

EL→JSON 反向时 `SwitchConditionParser.builderVO` 从 `SwitchCondition.getTargetList()` 各 `Executable.getTag()` 重建 `outletLabels`；子表达式已自带 tag 时 JSON→EL 不重复挂，保证往返幂等。

前端侧（`useElTreeModel.serializeNode`）对 SWITCH 未命名分支补默认 `caseN`（与投影展示一致），保证生成的 EL 每个 to 分支都带 tag；删 case 时 `outletLabels` 同步裁剪防错位。

## 5. 前端

- **物料**（`cmp-defs.ts`，group=business）：forLoop（`ph:number-circle-one`）/ iteratorLoop（`ph:shuffle`）/ switchRoute（`ph:signpost`），`lfNodeType` 联合类型扩为五种；FOR 算子 desc 修正为「FOR(计数器).DO(循环体)」。
- **叶子类型往返**（`useElTreeModel.ts`）：`parseNode`/`serializeNode` 用五种 LiteFlow 叶子类型白名单原样保留，注册名仍存 `componentCode`；删除无消费的 `forStart/forEnd/forStep` 死字段。
- **条件槽护栏**（`useCanvasController.ts`）：原「只收 boolean」Set 改为算子→条件件 `lfNodeType` 准入映射（IF/WHILE→布尔，FOR/ITERATOR/SWITCH→专属组件）；控制组件误拖普通位置、普通件误拖条件菱形都有定向提示。
- **属性面板**（`CmpProps.vue`）：
  - 条件件选择列表与「更换」弹层按所属算子过滤；
  - forLoop 小表单（count + indexVar）、iteratorLoop（source + indexVar）、switchRoute（source + 值→分支映射，target 下拉读当前 case 名，数字串自动转 number）；
  - SWITCH case 行内置改名输入框，写 `outletLabels[i]`，空白恢复默认名。
- **示例**（`mock-presets.ts`，均可纯本地试运行）：
  - `for-count`：FOR 3 轮写 `$i`，终值 2；
  - `iterator-items`：迭代 `$.items`，体内读 `$.items[$i].name`；
  - `switch-cases`：按 `$.code` 命中 caseA/caseB，非 A/B 报未匹配；
  - `iterator-nested`：双层 ITERATOR，外层 `$i` 内层 `$j`。

## 6. 限制与后续迭代

本档明确不做：

1. **BREAK 跳出**：EL 生成器（`generateBreak`）已支持 `.BREAK(布尔节点)` 拼接，但画布无 BREAK 物料与挂载交互；
2. **DEFAULT 兜底分支**：switchRoute 全不命中即抛错；后续可加默认出口（LiteFlow 支持 SWITCH(...).to(...).default(...) 与 `getTargetList` 之外的默认节点）；
3. **parallel 并行循环**：FOR/ITERATOR 的线程池/并行模式（`LoopCondition.setParallel`）不在本档，`$i` 索引当前按单线程顺序模型维护；
4. **ITERATOR 脚本节点**：引擎不支持，不提供物料。

## 7. 验证要点（试运行，用户亲跑）

静态校验已过（`mvnw -pl ruoyi-modules/ruoyi-databus -am compile`、oxlint、vue-tsc 无本模块新增错误）；运行时行为待试运行确认：

1. 四个 mock 示例分别跑通，步骤表 forLoop 摘要「共 3 次」、iterator 摘要「共 3 项」、switchRoute「命中分支 caseA」；
2. `for-count` 结果 `$.forLoop1.cursor = 2`；`iterator-items` 终值「丙」；`iterator-nested` 终值「乙一」；
3. SWITCH 入参改 `"code":"X"` 时业务异常含候选值清单；
4. 非法 `indexVar`（如 `1x`、与外层同名）在首轮执行前报错；
5. 保存后重开（JSON→EL→JSON 往返）：条件位 tag/data 不丢、SWITCH case 名不错位。

## 8. 关键代码索引

| 职责 | 位置 |
| --- | --- |
| 三组件 Cfg/Component + 循环支持工具 | `org.dromara.databus.component.flow`（ForLoopComponent、IteratorLoopComponent、SwitchRouteComponent、LoopSupport） |
| 索引 ThreadLocal 栈与路径替换 | `DatabusContext`（pushPendingLoopVar / reconcileLoopIndices / substituteLoopVars / clearLoopState） |
| 每节点前钩子同步 | `NodeStepResultCollector.postProcessBeforeNodeExecute` |
| 执行后清理 | `DatabusExecutor` 两入口 finally 段 |
| 条件位 tag/data、SWITCH 分支 tag | `el.parser.el.ForConditionParser` / `IteratorConditionParser` / `SwitchConditionParser`，`AbstractExpressParser` |
| 分支名编辑态字段 | `el.bean.Properties.outletLabels`（前端 `api/databus/el/types.ts` 同步） |
| 前端物料/护栏/表单/示例 | plus-ui：`cmp-defs.ts`、`useCanvasController.ts`、`CmpProps.vue`、`mock-presets.ts` |
