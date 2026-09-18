# 数据类组件登记本（setValue / fieldMap / dataPatch / response）

> 模块：ruoyi-databus `org.dromara.databus.component.data`
> 前端物料：plus-ui `src/views/databus/editor/cmp-defs.ts`（group=business）
> 最近更新：2026-09-18（新增 dataPatch）

数据类组件只操作 `DatabusContext`（内存 JSON 文档），不产生外部调用。BPM/HTTP 等外部交互组件见 BPM Connector 相关文档。

## 组件清单与职责边界

| 注册名 | 配置 | 一句话职责 | 产出 |
| --- | --- | --- | --- |
| `setValue` | `{path, value}` | 往**一个** JSONPath 写值（自动建路径，create 语义）；value 支持常量/裸路径/混合模板 | `$.path 指向处` |
| `fieldMap` | `{mappings:[{from,to,type?}]}` | **跨路径搬运**：from → to 原值复制，from/to 同含 `[*]` 时数组逐元素批量搬运，可选 int/string/boolean/double 转换 | `$.to 指向处` |
| `dataPatch` | `{target, patch}` | **merge 补丁**：把 patch 声明的字段覆盖到 target 命中的每个对象，未声明字段保留、缺失字段新增（update 语义） | `$.<tag>.patchedCount` |
| `response` | `{result, msg?, dataPath?}` | 设置链路返回，固定写 `$.response.result/msg/data` | `$.response.*` |

选型判断：写一个新值用 setValue；把 A 处数据搬到 B 处（含批量/类型转换）用 fieldMap；**已查出一批对象、只改其中几个字段再整体回写**用 dataPatch。

### 选型例子

**① 流程标题写一个常量/模板值 → setValue**

目标路径此前不存在也没关系（create 语义，自动建路径）。

```json
{ "path": "$.setValue1.title", "value": "申请-${$.request.code}" }
```

**② 把 HTTP 响应里的字段搬到自己的数据空间 → fieldMap**

值来自另一个路径，from/to 一一搬运；同含 `[*]` 时整组数组按索引搬，并顺手做类型转换。

```json
{ "mappings": [
  { "from": "$.httpRequest1.response.code", "to": "$.fieldMap1.code", "type": "int" },
  { "from": "$.httpRequest1.response.data[*].NAME", "to": "$.fieldMap1.items[*].name", "type": "string" }
] }
```

**③ 查出的记录只改两个字段再整体回写 BO → dataPatch**（本档核心场景）

记录里的 `ID` 等字段不动，boUpdate 才能按 ID 定位更新；改成查不到的条件时本节点直接报错，不会静默漏改。

```json
{ "target": "$.boQuery1.records[*]",
  "patch": { "BO_FIELD_USER": "$.request.newUser", "BO_FIELD_NUM": 99 } }
```

**④ 只改命中特定条件的那几条 → dataPatch + 过滤 target**

```json
{ "target": "$.boQuery1.records[?(@.BO_FIELD_NUM > 100)]",
  "patch": { "BO_FIELD_TEXT": "大额记录" } }
```

**⑤ 嵌套补丁一次改多层字段 → dataPatch + 嵌套 patch**

等价于分别写 `...[*].ext.flag` 和 `...[*].ext.source`，未列出的同级字段保留。

```json
{ "target": "$.boQuery1.records[*]",
  "patch": { "ext": { "flag": true, "source": "databus" } } }
```

> 边界辨析（jayway 2.10.0 + Jackson provider 实测）：
> - setValue 写 `$.records[*].X` 这类通配路径，**只有 X 在每条记录里都已存在时**才由 jayway 直接扇出成功；一旦 X 是新字段，set 抛错后 `createPath` 不认 `[*]`，会写出字面垃圾键——通配目标新增字段不可用 setValue。
> - setValue 的 create 语义（自动建新路径）只对**对象路径和数字索引数组**可靠：`createPath` 逐层（含末端叶子）预建占位 Map 再 set 覆盖；`[*]` / `[?(...)]` 不在其支持范围。
> - 因此：单字段、确定路径写值（含自动建路径）用 setValue；对一批/过滤命中的对象做**多字段 merge、允许新增字段、零命中要拦截、命中数要可观测**用 dataPatch；跨位置改名搬运用 fieldMap。

## dataPatch 契约（2026-09-18 拍板）

### 配置示例

```json
{
  "target": "$.boQuery1.records[*]",
  "patch": {
    "BO_FIELD_USER": "$.request.newUser",
    "BO_FIELD_NUM": 99
  }
}
```

> patch 里的键没有任何框架保留字，写什么就是往命中记录上合并什么字段；BPM BO 记录是扁平列，常规配置就全部平铺。只有目标字段本身是对象子结构时才需要嵌套写法，见上文例子⑤。

- `target`（必填）：完整 JSONPath，必须指向**对象或对象数组**。三种命中形态：
  - `$.x.records[*]`：数组全量；
  - `$.x.records[0]`：指定索引；
  - `$.x.records[?(@.ID=='123')]`：按过滤表达式命中。
- `patch`（必填，非空）：merge 补丁对象。键为相对 target 的字段名，支持嵌套对象（在命中对象上递归深合并）；键名只允许 `[A-Za-z_][A-Za-z0-9_]*`。叶子值走统一 `resolveParam`（常量 / 裸路径整取 / `${$.path}` 混合模板）；数组作为整体叶子值写入；嵌套结构内不再递归解析其中字符串路径（与 setValue 行为一致）。

### 语义规则

1. **merge 而非 replace**：只覆盖/新增 patch 声明的字段，BO 记录的 `ID` 等未声明字段原样保留——这是它能插在 boQuery 与 boUpdate 之间的关键（boUpdate 要求每条记录含 ID）。嵌套 patch 做深合并，目标已有的同级兄弟键保留。
2. **target 零命中即报错，patch 字段缺失则新增**：target 路径不存在、过滤无结果、数组为空都抛 ServiceException（update 语义安全网，防止静默漏改）；但命中对象本身缺少 patch 里的字段时会正常新增（与 target 是否存在是两回事）。
3. **原地更新**：补丁直接打在 target 命中的对象上，不产生副本。下游 boUpdate 继续读同一 sourcePath 即可。
4. 命中对象数写入 `$.<tag>.patchedCount`（单对象为 1，数组为 size），供试运行快照核对。

### 实现机制（jayway 2.10.0 + Jackson provider，2026-09-18 实测）

**不要**把补丁叶子拼成 `target + ".字段名"` 交给 `DocumentContext.set`。实测九种路径（生产同款 `JacksonJsonProvider`）结论：jayway `set` 只改**求值时已存在的属性节点**，不创建任何缺失节点——

| set 路径形态 | 结果 |
| --- | --- |
| `$.records[*].X`（X 已存在） | 成功，扇出到每条记录 |
| `$.records[*].NEW`（NEW 缺失） | PathNotFoundException |
| `$.records[?(@.ID=='2')].X`（X 已存在） | 成功，仅命中记录被改 |
| `$.records[?(@.ID=='2')].NEW`（NEW 缺失） | PathNotFoundException |
| 过滤零命中 / 空数组 / 父路径缺失 / 单对象新键 | 全部 PathNotFoundException |

而 `DatabusContext.write` 捕获异常后的 `createPath` 兜底只认 `[数字]` 索引，不认 `[*]` / `[?(...)]`——对通配叶子路径兜底会写出字面名为 `records[*]` 的垃圾键。

dataPatch 的实际做法：`read(target)` 取出命中对象。Jackson provider 的 read 返回文档底层 `LinkedHashMap` 引用（`[*]` 与 `[?(...)]` 命中的元素同为引用，已实测：对引用 put 后 `jsonString()` 立即反映），于是直接在引用上递归 `put` 深合并即可，缺失键天然新增、零拷贝；最后只用确定路径 `$.<tag>.patchedCount` 写计数。

> 后续若有"基于旧值算新值"（拼接、自增）需求，jayway 另有 `DocumentContext.map(path, MapFunction)` 可扩展，当前未使用。

### 典型链路

查改验证（保留数据，可去 BPM 后台核对、可重复跑）：boQuery（查记录）→ dataPatch（改 USER 等字段）→ boUpdate（按 ID 整体回写）→ boQuery（同条件二次查询验证落库）。
示例：`plus-ui/src/views/databus/editor/mock-presets.ts` 的 `bpm-bo-update`；看完效果后用 `bpm-bo-delete`（条件查询 → boDelete）单独清理，删除不与演示链耦合。

## 旧系统 Doc*Processor 迁移说明

旧系统 `com.awspaas.user.apps.data.bus` 中：

- `DocCreateProcessor` / `DocUpdateProcessor` 都是**空壳**（仅继承 BaseProcessor，`process()` 为空）；
- 有实现的 `DocBatchCreateProcessor` / `DocBatchUpdateProcessor` 代码**完全相同**：读 `meta: [{path, value, dataType}]` 循环 `document.set`，create/update 无行为差异。

新系统不照搬这四个处理器：单条/多条"按路径写值"已由 setValue 覆盖（路径间搬运用 fieldMap），真正的缺口是"对查出的对象数组做 merge 部分更新"，由 dataPatch 补齐。旧配置迁移映射：

| 旧 meta 条目形态 | 新系统 |
| --- | --- |
| 单条 `{path, value}` | setValue |
| 多条平铺写值 | 多个 setValue，或 fieldMap（值来自其他路径时） |
| 对一批对象改部分字段 | dataPatch |
