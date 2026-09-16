# Databus 画布渲染层迁移设计：从物理容器范式到网关节点范式

> 所属模块：前端编排设计器（`plus-ui` 仓库 `src/views/databus/editor/`），配合后端模块 `ruoyi-databus`
> 更新日期：2026-09-15
> 关联文档：[databus-el-express-parser.md](databus-el-express-parser.md)（后端 EL ↔ CmpProperty 树，本设计**不改动**该契约）
> 参考实现：`liteflow-editor-client`（基于 AntV X6 的官方同款编辑器）、Vue Flow 官方文档

## 1. 背景：为什么要迁移

### 1.1 现状：物理容器范式

当前前端把 CmpProperty 树渲染成**物理嵌套的矩形容器**：THEN/IF/SWITCH 等算子是 360×140 的大盒子节点，业务组件靠 Vue Flow 的 `parentNode` + `extent: 'parent'` 物理嵌套在盒子内部，分支语义靠盒子内的 slot 区域表达。

### 1.2 该范式已暴露的系统性问题

2026-09-15 一天内连续修复的 5 个缺陷，全部是容器范式独有的"税"：

| # | 问题 | 根因 |
| --- | --- | --- |
| 1 | 拖业务组件进 SWITCH 的 case 报 `NODE_EXTENT_INVALID` | 子节点 `dimensions` 未测量时设 `extent:'parent'`，Vue Flow 内部校验失败 |
| 2 | 子节点出现在算子 z-index 下层被遮挡 | 父容器 `box-shadow`/`overflow` 与子节点 wrapper 的层叠竞争 |
| 3 | 父容器被子节点 `expandParent` 撑大后无法缩回 | 容器尺寸与子节点边界强耦合，需额外引入 NodeResizer 补救 |
| 4 | 自动排列必须递归算子子图、算 relX/relY | 容器内坐标是相对父的局部坐标系，dagre 无法一次排全图 |
| 5 | drop 落点要做 slot 命中检测 | 依赖容器 `dimensions` 异步测量，首帧漏判；`detectSlotAt` 逻辑复杂且与 9 种算子的 slot 划分耦合 |

这些不是孤立 bug，而是"用嵌套分组特性（Nested Nodes）表达控制流语义"的范式错配。

### 1.3 官方定位佐证

- Vue Flow [Nested Nodes](https://vueflow.dev/examples/nodes/nesting.html) 的定位是"nested nodes / nested flows"，配套 NodeResizer，适用场景是**可折叠分组框、子流程框**这类"一组节点要被框在一起整体移动"的需求，不是 IF/SWITCH 控制流。
- Vue Flow 官方 [Custom Nodes 示例](https://vueflow.dev/guide/handle.html#multiple-handles)（ColorSelectorNode）表达分支的标准做法是：**一个平级小节点 + 多个 source handle**，分支归属存在边的 `sourceHandle` 上，分支节点全部平级。
- Vue Flow 官方[布局指引](https://vueflow.dev/examples/layout/simple.html)明确推荐 dagre 排**平级节点**："Vue Flow does not have a built-in layouting system, but it's easy to use dagre to layout our nodes"。

### 1.4 同款产品佐证

`liteflow-editor-client`（LiteFlow 官方同款编辑器，X6 实现）用的就是网关范式：算子是 30×30 小图标节点或干脆不渲染（THEN 只连边），业务节点全部平级，分支靠扇出/扇入边 + 汇合锚点表达。第 4 章详述其机制。

## 2. 目标与非目标

### 2.1 目标

1. 画布渲染层改为**平级节点 + 语义边**（gateway 范式），消除 parentNode/extent/expandParent 整套嵌套机制。
2. 算子视觉上是小网关节点（可做菱形/圆形等形状），业务节点保持带文字的矩形卡片。
3. dagre 一次布局整张图，删除容器子图递归、slot 命中、相对坐标换算。
4. 算子编辑（增删分支、删节点、拖入）以"节点 + 边"为操作对象，复用 Vue Flow 原生交互。
5. **后端零改动**：`CmpProperty` 树结构、EL 生成/解析接口完全不变。

### 2.2 非目标

- 不做多画布/子流程嵌套页（nested flows）——将来若需要"可折叠分组框"，再以独立特性引入 Nested Nodes，不与控制流混用。
- 不改后端 EL 引擎、不改 CmpProperty JSON 结构、不改接口。
- 不做手画自由连线回写模型（见 9.2 开放问题）。
- 本期不强制做循环回边的视觉闭环（FOR/WHILE 语义仍由树表达，见 9.1）。

## 3. 分层架构：数据层不动，渲染层重写

```
┌─────────────────────────────────────────────────────────────┐
│  数据层（契约，不动）                                          │
│  CmpProperty 树  ←─→  LiteFlow EL 字符串                     │
│       ▲                    （后端 databus-el-express-parser） │
│       │ 前端双向转换（本次重写：cmp-tree.ts）                   │
├───────┼─────────────────────────────────────────────────────┤
│  渲染层（本次重写）                                            │
│  Vue Flow 平级 nodes[] + edges[]                              │
│    业务节点 / 算子网关节点 / 虚拟锚点（汇合点·空槽占位）          │
│    边的 sourceHandle + label 表达分支                         │
└─────────────────────────────────────────────────────────────┘
```

**关键不变量**：保存时提交给后端的永远是从当前画布语义重建出的 CmpProperty 树；后端无从感知画布是容器还是网关。

## 4. 参考实现调研：client 是怎么摊平的

client 的模型是一棵 ELNode 树（与 CmpProperty 同构），`toCells()` 递归把树**摊平**为 X6 的平级 nodes + edges。核心机制是每个算子都对外暴露 `getStartNode()` / `getEndNode()` 两个"端口"，父算子只连接子算子的首尾：

| 算子 | 是否建画布节点 | 摊平规则 |
| --- | --- | --- |
| 业务叶子（NodeOperator） | 建 1 个 | 普通矩形/图标节点 |
| **THEN** | **不建** | 只在相邻子项间连边：`child[i].end → child[i+1].start` |
| IF | condition 叶子充当网关 + 1 个汇合点 | 网关出两条边（label=true/false）到真/假分支首节点，两分支末节点连汇合点；空槽用虚拟占位节点 |
| SWITCH | condition 叶子充当网关 + 1 个汇合点 | 网关出 N 条边（label=case 名）到各分支，末尾汇合 |
| FOR/WHILE/ITERATOR | condition 叶子充当网关 + 1 个汇合点 | 图结构与 SWITCH 同构，**不产生循环回边**，循环语义只存在于树/EL |
| WHEN | 自建开始网关节点 + 1 个汇合点 | 网关扇出到各并行分支，末尾扇入汇合 |
| CATCH | 自建网关节点 + 1 个汇合点 | 第 1 路（try）+ 第 2 路（label="异常"），末尾汇合 |
| AND/OR/NOT | 自建网关节点 + 汇合点 | 扇出 2 路（AND/OR，边 label `+`/`*`）或 1 路（NOT，边 label `-`），末尾汇合 |
| CHAIN | 建全局开始/结束节点 | 串联各 children |

**三类虚拟元素**：

1. **汇合锚点（IntermediateEnd）**：小圆点，让分支图重新收敛为单端口，父层级才能用"首尾端口"递归连接。不可单独删除。
2. **空槽占位（ELVirtualNode）**：IF 假分支/CATCH 异常槽为空时的占位拐弯点，拖入真实组件时替换它。
3. **开始/结束（ELStartNode/ELEndNode）**：全局唯一起止，挂在网关上的代理对象，CRUD 转发给真实算子。

**client 的数据流模式（注意：不照抄）**：client 中 ELNode 树是唯一数据源，画布只是投影——任何编辑都改模型树，然后 `graph.resetCells(model.toCells())` 整体重渲染 + `forceLayout`。它**不存在 graph→树的反推算法**。该模式依赖 X6 的 resetCells；Vue Flow 语境下整体替换 nodes 会丢失拖拽手感、选中态与撤销栈，故只借鉴其"摊平规则"，不照搬其更新模式（见第 7 章）。

**client 布局参数**：`@antv/layout` DagreLayout，`rankdir:'LR'`，节点统一 30×30，ranksep/nodesep=20，并按 BFS 层级做了二次 x 对齐。本项目不照搬尺寸与方向（见 6.3）。

## 5. 渲染层模型设计

### 5.1 节点种类（全部平级，无 parentNode）

| 节点类型 | type | 尺寸（建议） | 形状 | 可删 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 业务组件 | `cmp` | 150×56 | 圆角矩形卡片（现状保留） | 是 | httpRequest 等，带 label + cmpId |
| 算子网关节点 | `gateway` | 56×56 | 菱形/圆形/图标（按算子，见 5.4） | 是（级联，见 8.3） | WHEN/CATCH/AND/OR/NOT 自建；IF/SWITCH/循环由条件组件充当 |
| 条件组件网关 | `cmp`（标记 `isCondition`） | 56×56 或沿用业务卡 | 菱形 | 随算子 | IF/SWITCH/循环的 condition 组件本身就是网关，承载出分支的 handle |
| 汇合锚点 | `junction` | 16×16 | 实心小圆点 | 否（随分支结构增删） | IntermediateEnd 等价物 |
| 空槽占位 | `placeholder` | 150×56 虚线框 | 虚线矩形 | 拖入即替换 | IF 假分支/CATCH 异常槽等为空时 |
| 开始/结束 | `cmp`（virtual 现状保留） | 现状 | 现状 | 否 | start/end 虚拟节点 |

> 说明：IF/SWITCH/循环的"网关"就是其 condition 组件节点本身（与 client 一致），该节点需要多出口 handle，故渲染为菱形并暴露多个 source handle；WHEN/CATCH/AND/OR/NOT 没有条件组件，由独立的 `gateway` 节点承担扇出。

### 5.2 边的语义

- 边仍是 `{ id, source, target, sourceHandle?, targetHandle?, data: { branchLabel?, kind? } }`。
- **分支归属用 `sourceHandle` 表达**：网关每个出口一个带 id 的 handle（IF: `true`/`false`；SWITCH: case 名；CATCH: `try`/`catch`；AND/OR: `a`/`b`；NOT: `a`）。
- 分支提示用边 label（真/假/case 名/异常/+/*/-），label 是展示副本，权威归属以 sourceHandle 为准。
- 边的 `data.kind` 区分：`seq`（顺序边）、`branch`（分支扇出）、`merge`（汇入锚点）、`jump`（空槽占位连接，保存时丢弃）。

### 5.3 各算子的平级图展开规则（前端投影规范）

与第 4 章 client 表一致，落地到本项目：

- **THEN**：不产生网关节点。子项链用 `seq` 边首尾串联。
- **WHEN**：gateway（圆形，并行网关）→ 各子项首节点（branch 边）；各子项末节点 → junction（merge 边）。
- **IF**：condition 菱形（两出口 true/false）→ 两分支首节点；两分支末节点 → junction。空假分支用 placeholder，其 branch 边 kind=jump。
- **SWITCH**：condition 菱形（N 个出口=cases）→ 各分支；末尾 → junction。支持增删 case = 增删 handle 与对应占位。
- **FOR/WHILE/ITERATOR**：condition 菱形 → DO 分支 → junction；BREAK 条件作为 DO 链上的特殊节点或属性（本期保持属性面板编辑，图上不画回边）。
- **CATCH**：gateway（异常网关）两路 try/catch → junction。
- **AND/OR**：gateway（圆形，标注 &/≥1）两路；**NOT**：单路。边带 +/*/- 标签。
- **CHAIN**：引用子链，渲染为一个业务卡样式的引用节点（现状语义保留）。

### 5.4 算子形状（顺带解决"全是方框"问题）

网关变小后不再受容器 bounding rect 约束，可自由做形状（纯 CSS，零依赖）：

- 决策类（IF/SWITCH 及其 condition）：`clip-path: polygon(50% 0,100% 50%,50% 100%,0 50%)` 菱形。
- 并行/逻辑（WHEN/AND/OR）：`border-radius:50%` 圆形，内放图标或字符（+、&、∥）。
- NOT：圆形 + `!` 图标。
- 循环（FOR/WHILE/ITERATOR）：菱形或圆角矩形 + 循环箭头徽标。
- CATCH：菱形/矩形 + 闪电图标。
- 业务组件：保持矩形卡片，靠颜色/图标区分（与现状一致）。

handle 挂在形状的视觉顶点上（菱形：上 target、下/左右多个 source，用 CSS 绝对定位微调）。

## 6. 关键流程设计

### 6.1 回显：CmpProperty 树 → 平级图（重写 `fromCmpProperty`）

递归下降，为每个算子按 5.3 规则产出网关/junction/占位节点与边；每个递归单元返回 `{ startNodeId, endNodeId }` 两个端口，父单元只连接子单元的端口（client 的 getStartNode/getEndNode 模式）。坐标先全部给 0，再统一交给 dagre 布局。

### 6.2 保存：直接序列化模型树（方案 B，无反推）

- 模型树是唯一数据源（见第 7 章），所有编辑已落到树上。
- 保存时直接把模型树按后端契约转成 `CmpProperty`，**不读 Vue Flow 的 nodes/edges**。
- `toCmpProperty` 退化为"模型 ELNode → CmpProperty"的纯字段映射，无图论、无拓扑、无反推算法。
- 现有 `index.vue` / `useElPreview.ts` 调用点签名保持不变（仍传 nodes/edges 作签名兼容，内部忽略，直接读模型树），降低迁移波及面。

> 由于不再从图反推，第 9.2 的"禁止自由连线"约束从"反推安全"降级为"投影一致性"——仍建议保留 `isValidConnection` 白名单，但即使被绕过，模型树也不会被污染（最坏情况是画布视图与树不一致，重新投影即恢复）。

### 6.3 自动布局：dagre 一次排全图（简化 FlowLayoutButton）

- 平级图所有真实节点 + junction 一起进 dagre（placeholder 也进，保证空分支占位有位置）。
- `rankdir:'TB'`（与现有 handle 上入下出一致；不采用 client 的 LR，避免重做 handle 方位与全文交互）。
- 建议参数：业务卡 150×56、网关 56×56、junction 16×16；nodesep 40、ranksep 60（沿用现有视觉密度），可按实测微调。
- 删除现有"递归算子子图 + slot 分组 + relX/relY"整段逻辑。

### 6.4 拖入新组件（重写 insertNodeAt / detectSlotAt）

- 拖到**画布空白**：作为独立顺序节点，连在当前选中/末尾链路（沿用 append 语义），无需任何命中检测。
- 拖到**空槽占位 placeholder**：替换占位（client 的 replace 语义），自动接到对应网关出口。
- 拖到**网关/分支的某个出口 handle**：作为该分支新叶子；SWITCH 可通过"添加 case"按钮先建占位。
- **删除 `detectSlotAt`、`parseStyleSize` 及对容器 dimensions 的依赖**。

## 7. 编辑状态管理：方案 B（已定）—— 模型树为唯一数据源 + 投影

经评审选定 **方案 B**：前端维护一棵与 client ELNode 同构的模型树作为唯一数据源，所有编辑动作改树，树→图单向投影。这是 client 的做法，也彻底消除"图反推树"的算法复杂度。

### 7.1 三个候选方案回顾

| 方案 | 状态源 | 保存 | 成本 |
| --- | --- | --- | --- |
| A 纯受控图 | nodes/edges | 保存时拓扑反推 | 反推算法复杂，非法图风险 |
| **B 模型树 + 投影（已选）** | **ELNode 树** | **直接序列化树** | **投影需 reconcile，撤销/选中也需协调** |
| C 图 + 网关元数据 | nodes/edges | 读网关 data 反推 | 反推变成查表，但两套字段需同步 |

### 7.2 方案 B 落地设计

**ELNode 模型树**（新建，借鉴 client）：

```ts
// 仿 client ELNode，但保留本项目字段命名
interface ElNode {
  id: string;                  // 画布稳定 id（用于 reconcile）
  type: string;                // THEN/IF/SWITCH/.../NodeComponent
  cmpId?: string;              // 业务组件的 LiteFlow nodeId
  condition?: ElNode;          // IF/SWITCH/循环 的条件位节点
  children?: ElNode[];         // 子分支
  tag?: string;
  data?: string;
  // 元数据：用于投影时定位 handle、case 名等
  branchLabel?: string;        // 该节点在父算子中的分支标签（true/false/caseN/异常/+/*/-）
  parentOperatorId?: string;   // 所属算子的 id（便于 reconcile 反向查找）
}
```

**投影（Project）= 树 → Vue Flow nodes/edges**：

- 与 client `toCells()` 同构：递归下降，每个算子按 5.3 规则产出网关/锚点/占位/边；每个递归单元返回 `{ startId, endId }`。
- 关键：**按 ElNode.id 做稳定 reconcile**，避免每次编辑整体重渲染。
  - 首次投影：树→nodes/edges 全量产出。
  - 后续编辑：复用既有 nodes 的 position（用户拖拽后的坐标）和 selected 状态，仅对结构变化部分做增删；可用简易 id diff（O(n) 比对两版 nodes 的 id 集合）。
  - 复杂度控制：本期不做细粒度 reconcile，**编辑后整体重投影**（保留 position map 缓存：`id→{x,y}`，重投影时按 id 恢复坐标），代价是选中态丢失——后续 M2 评估是否升级到 id-keyed diff。
  - 坐标缓存策略：`{ id, x, y }` Map，投影前读旧图存档，投影后按 id 回填；新节点走 dagre 局部布局或默认位置。

**编辑动作（控制器统一入口）**：

- 增删 case、删网关、替换占位、拖入新叶子：全部改为"改树" → 触发重投影。
- Vue Flow 的拖拽位置（节点 position）在拖拽结束时回写到 ElNode 的 cachedPosition（仅缓存，不参与 EL 序列化）。
- 撤销栈：以 ElNode 树快照为最小单位（替代现状的 nodes/edges 快照），快照更小、语义更稳。
- 选中态：保留 Vue Flow 自己的 selected 机制，投影时按 id 恢复（仅是视觉态，与树分离）。
- `isValidConnection` 仍做白名单（投影一致性保险），但即使绕过也不污染树。

**与现状的关系**：

- `useFlowHistory` 改为对 ElNode 树快照（而非 nodes/edges）。
- `useCanvasController` 拆出 `useElTreeModel`（树 CRUD + 投影 + 缓存）。
- `toCmpProperty` 不再读 nodes/edges，改为 `elTree → CmpProperty`（纯字段映射）。
- `fromCmpProperty` 改为 `CmpProperty → elTree → 投影产出 nodes/edges`（两段式）。

### 7.3 方案 B 的代价与对策（诚实清单）

| 代价 | 对策 |
| --- | --- |
| 自研 reconcile 层（哪怕只做 id 缓存恢复） | M1 先做整体重投影+坐标缓存，够用即可；不预先自研 diff |
| 撤销栈语义重写 | useFlowHistory 切换数据源到 ElNode，单测保证 |
| 拖拽手感：投影后节点是"新对象"，Vue Flow 可能丢失拖拽中的 inertia | 拖拽期间不触发重投影，拖拽结束 onNodeDragStop 时回写 cachedPosition 再投影 |
| 选中态在整体重投影后丢失 | 投影前后比对 selectedIds，按 id 恢复 selected |
| 坐标缓存与树结构不一致（删节点后再加同 id 新节点） | ElNode.id 用 uuid，不回收；新增节点用新 uuid，避免误用旧坐标 |
| 整体重投影在百节点规模可能卡顿 | 本期编排图通常 < 50 节点，整体替换成本可接受；M3 评估性能后再决定是否升级 diff |

## 8. 影响面与实施计划

### 8.1 文件级影响面（plus-ui `src/views/databus/editor/`）

| 文件 | 处置 | 说明 |
| --- | --- | --- |
| `cmp-tree.ts` | **重写** | CmpNodeData 字段调整（去 container/collapsed/slot 父权语义，加 gatewayKind/outlets/isCondition）；`fromCmpProperty` 改两段式（CmpProperty→ElNode→投影）；`toCmpProperty` 改为 `ElNode → CmpProperty` 纯字段映射（不读 nodes/edges）；删容器尺寸常量，加网关/锚点尺寸 |
| `composables/useElTreeModel.ts` | **新建** | 方案 B 核心：ElNode 树 CRUD、Project（树→nodes/edges 投影）、坐标缓存、id-keyed 选中态恢复 |
| `composables/useCanvasController.ts` | **大改** | 编辑动作改为"改树 → 触发重投影"；删 detectSlotAt/parseStyleSize；新增"替换占位""增删 case""网关级联删除""分支自动接线" |
| `composables/useFlowHistory.ts` | **重写** | 数据源从 nodes/edges 切换到 ElNode 树快照（更小、更稳） |
| `components/CmpNode.vue` | **拆分** | 去除算子容器分支与 NodeResizer；新增 `GatewayNode.vue`、`JunctionNode.vue`、`PlaceholderNode.vue`（符合组件高内聚约定）；业务卡保留；网关形状见 5.4 |
| `components/FlowCanvas.vue` | 小改 | nodeTypes 注册新类型；保留 ConnectionMode.Loose；isValidConnection 保留白名单作为投影一致性保险 |
| `components/FlowLayoutButton.vue` | **简化** | 删除递归子图逻辑，回到官方一次 dagre 排全图 |
| `components/CmpContextPad.vue` / `CmpContextMenu.vue` | 小改 | 入口适配新节点类型；junction/placeholder 不出操作按钮 |
| `components/CmpProps.vue` | 小改 | 属性面板直接改 ElNode（不再回写 nodes data）；SWITCH cases 编辑改走 outlets |
| `components/FlowOutline.vue` | **简化** | 大纲树直接遍历 ElNode（树即数据源，无需再从图重建） |
| `mock-presets.ts` | 重写示例 | 改为 ElNode 树样例 |
| `cmp-defs.ts` | 扩展 | 每个算子补 gatewayKind、形状、outlets 定义（label 已有） |
| `index.vue` / `useElPreview.ts` | 小改 | 调用点签名兼容（仍传 nodes/edges 但内部读模型树），或改为显式从 useElTreeModel 取树 |
| `@vue-flow/node-resizer` 依赖 | **移除** | 迁移完成后卸载（容器消失，NodeResizer 无用途） |

### 8.2 里程碑（按方案 B 调整）

- **M0 设计评审（本次）**：已定方案 B；仍需确认节点尺寸、循环回边取舍、手画连线策略、整体重投影 vs id-diff 取舍（建议先整体重投影）。
- **M1 模型树 + 投影 + 回显**：新建 `useElTreeModel`（ElNode 树 + Project + 坐标缓存 + 选中态恢复）；`fromCmpProperty`/`toCmpProperty` 改两段式；新节点组件（网关/锚点/占位）与形状。验收：现有 mock JSON 回显为平级网关图；保存时 EL 预览字符串与迁移前**逐字符一致**（经后端 verify 合法）。
- **M2 编辑能力**：编辑动作改为"改树 → 重投影"（拖入、替换占位、网关级联删除、增删 case、复制粘贴、撤销重做、右键菜单、属性面板）。验收：第 1 章 5 个问题全部不复现；整体重投影后选中态/拖拽位置按 id 恢复，手感不退化。
- **M3 布局与连线**：dagre 一次全图布局；isValidConnection 白名单；分支 label；循环类视觉。验收：自动排列一键规整；非法连线被拒（但绕过也不污染树）。
- **M4 收尾**：大纲改为遍历 ElNode；mock 重做；移除 NodeResizer 与容器残留代码/样式；若 M3 性能数据显示百节点规模卡顿则升级到 id-diff；oxlint + vue-tsc 全绿；更新本文档。

### 8.3 级联删除规则（替换现有"按 parentNode 递归"）

删除一个网关节点时：删除其 gateway、关联 junction、该网关所有出边；分支内的业务子树按编辑策略决定（默认随网关删除，与现状"删算子级联删子节点"一致；后续可加"提升到外层"选项）。删除业务叶子：仅删其节点与相连边，并把前驱后继用 seq 边自动重接（保持链路连通）。junction/placeholder 不允许手动删除。

## 9. 风险与开放问题（评审需拍板）

### 9.1 FOR/WHILE 循环回边是否画出来？

client 选择不画（图上与 SWITCH 同构，循环只存在 EL 层）。建议本期跟随：dagre 对有环图需配 `--rankdir` 外的 breakpoints/或手动 edge rank，成本不小且回边易与正常分支视觉混淆。列为 M3 之后的可选增强。

### 9.2 是否允许用户手画任意连线？

方案 B 下，模型树是唯一数据源，画布只是投影，因此**即使允许手画连线也不会污染树**——最坏情况是画布多出一条边，下次投影（任何编辑动作触发）即被清除。但为了用户编辑体验一致性，仍建议：

- 保留 `isValidConnection` 白名单作为投影一致性保险（避免用户画了立即被清除造成困惑）。
- 本期不允许"自由拉边"建立结构关系——结构变更只能经编辑器动作（拖入、增删 case、替换占位）。
- 未来若要做"自由连线模式"，需要让连线动作回写树（类似 client 的 attach/detach），届时再开放。

### 9.3 节点尺寸与密度

业务卡 150×56 比 client 的 30×30 信息更全（中文名+cmpId），保留；网关 56×56。若实测图过宽，M3 可评估业务卡紧凑态（仅图标，hover 展开文字）。

### 9.4 WHEN 的视觉语义

WHEN 是"并行"，网关扇出即可表达；是否需要在分支上画"并行开始/结束"双杠（BPMN 平行四边形网关记号）作为装饰，M1 视觉走查时定。

### 9.5 整体重投影 vs id-diff（方案 B 取舍）

M1 先做整体重投影 + 坐标缓存（实现快、够用）。风险：选中态在重投影瞬间丢失（需按 id 恢复）；百节点规模可能卡顿。M3 视性能数据决定是否升级到 id-keyed diff（按 ElNode.id 比对两版 nodes 集合，只增删变化部分）。**不在 M1 预先自研 diff**，避免过度设计。

## 10. 验收标准（迁移完成的定义）

1. 现有全部 mock/真实 EL 回显后，EL 预览字符串与迁移前**逐字符一致**（经后端 verify 合法）。
2. 第 1.2 节 5 个缺陷在 9 种算子上均不复现；无 `NODE_EXTENT_INVALID`、无层级遮挡、无容器尺寸失控。
3. 拖入/删除/增删分支/复制粘贴/撤销重做/自动排列全部基于平级节点工作，无 parentNode/extent/expandParent 残留。
4. dagre 一键布局整张图，无递归子图代码。
5. IF/SWITCH 菱形、WHEN/AND/OR 圆形等形状生效，物料与画布节点不再"全是方框"。
6. **方案 B 特有**：画布视图始终可由 ElNode 树重投影复现（删边/乱拉不污染树，重投影即恢复）；撤销栈以 ElNode 树为快照单位；节点拖拽位置经 cachedPosition 回写并跨重投影保留（按 ElNode.id 恢复）。
7. oxlint 0 错误、vue-tsc 0 新增错误、NodeResizer 依赖移除。
