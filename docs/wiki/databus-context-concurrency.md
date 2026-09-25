# DatabusContext 并发安全：WHEN 并行写共享数据空间

> 2026-09-24 定位，P0。本篇是**缺陷 + 修复档**（对标 [liteflow-el-normalize-bug.md](liteflow-el-normalize-bug.md) 的体裁）。
> 结论先行：**不改 jayway 源码**，在 `DatabusContext` 内加一把读写锁。修复必须**前置于执行记录页**。
> 上下文机制本身见 [databus-context-design.md](databus-context-design.md)，本篇只讲并发维度。

## 1. 结论（TL;DR）

`DatabusContext` 底层的 jayway 文档是普通 `LinkedHashMap` / `ArrayList`，**整个 databus 模块没有任何锁**。LiteFlow 的 `WHEN` 会把子节点扔进线程池并行执行，多个分支同时 `write()` 时对**同一个根 Map 做结构性修改**——这是真 bug，不是理论风险，种子数据里已有活体触发点。

修复方式：`DatabusContext` 内加 `ReentrantReadWriteLock`。

- **读锁**：`read* / resolve / resolveMixedPath / exists / snapshotDataSpace / toJsonString / getConnection / allConnections`
- **写锁**：`write / save / createPath / registerConnection`

无竞争时开销纳秒级，相对一次 HTTP 调用可忽略；**对外 API 与语义零变化，用户无感**。

已否决两个方案：换 `ConcurrentHashMap`、"约束分支只写自己子树"。否决理由见 §6。

## 2. 竞争路径逐步拆解

### 2.1 数据结构

```java
// DatabusContext.java:47-52 —— Jackson 作为 provider
private static final Configuration CONFIGURATION = Configuration.builder()
    .jsonProvider(new JacksonJsonProvider())
    .mappingProvider(new JacksonMappingProvider())
    .build();

// DatabusContext.java:113-118 —— 空上下文即裸 LinkedHashMap
public static DatabusContext empty() {
    return new DatabusContext(JsonPath.using(CONFIGURATION).parse(new LinkedHashMap<>()));
}

// DatabusContext.java:57 —— 连接表同样是裸 LinkedHashMap
private final Map<String, Connection> connections = new LinkedHashMap<>();
```

`JacksonJsonProvider` 反序列化出来的嵌套层也是 `LinkedHashMap` / `ArrayList`。**整棵树从根到叶没有一个线程安全容器。**

### 2.2 写入链路

```java
// DatabusContext.java:199-207
public void write(String path, Object value) {
    String resolvedPath = substituteLoopVars(path);
    try {
        document.set(resolvedPath, value);
    } catch (Exception e) {
        createPath(resolvedPath);          // ← 路径不存在时走这里
        document.set(resolvedPath, value);
    }
}
```

`createPath` 逐层建路径：

```java
// DatabusContext.java:493-499
private void handleObjectPath(StringBuilder currentPath, String part) {
    String nextPath = currentPath + "." + part;
    if (!PathResolver.pathExists(document, nextPath)) {
        document.put(currentPath.toString(), part, new LinkedHashMap<>());   // ← 竞争点
    }
    currentPath.append(".").append(part);
}
```

当 `currentPath` 还是 `"$"` 时，这一行就是**往根 Map 塞新顶层键**。

### 2.3 什么叫"子树父键"

以 `$.httpA.result.data` 为例：

```
$                      ← 根 Map（唯一，共享）
└── httpA              ← 顶层键 = "子树父键"
    └── result
        └── data
```

**两种情况，只有一种有竞争：**

| 情况 | 各线程做什么 | 是否竞争 |
| :--- | :--- | :--- |
| 父键 `httpA` **已存在** | 线程各自往**不同的子 Map** 里写 | ❌ 无竞争（不同对象） |
| 父键 `httpA` **需新建** | 三个线程同时 `document.put("$", "httpA", ...)` | ✅ **竞争同一个根 Map** |

节点产出统一挂 `$.<tag>.xxx`（见 `.trae/project_memory.md` §四「实例标识」），而 `tag` 在链路里唯一——**每个节点第一次写产出时，必然要新建自己的顶层键**。所以只要 `WHEN` 里有多个节点，就必然落在第二行。

### 2.4 数组路径同样裸奔

```java
// DatabusContext.java:502-518
private void handleArrayPath(StringBuilder currentPath, String part) {
    ...
    List<Object> list = document.read(arrayPath);   // 拿到的是**活的** ArrayList 引用
    while (list.size() <= index) {
        list.add(new LinkedHashMap<>());            // ← 原地扩容，无同步
    }
    document.set(arrayPath, list);
}
```

两个线程同时扩同一个 `ArrayList`：`size` 与 `elementData` 不一致 → 丢元素、`null` 空洞、`ArrayIndexOutOfBoundsException`。

## 3. 活体触发点（不是理论风险）

[databus_chain_mock_data.sql:65](../../script/sql/databus_chain_mock_data.sql) 的 `when-parallel` 链路：

```jsonc
{"type":"WHEN","children":[
  {"id":"setValue", "properties":{"tag":"setValue1","data":"{\"path\":\"$.setValue1.out\",\"value\":\"并行 A\"}"}},
  {"id":"setValue", "properties":{"tag":"setValue2","data":"{\"path\":\"$.setValue2.out\",\"value\":\"并行 B\"}"}},
  {"id":"setValue", "properties":{"tag":"setValue3","data":"{\"path\":\"$.setValue3.out\",\"value\":\"并行 C\"}"}}
]}
```

三个工作线程同时执行 `write("$.setValueN.out", ...)`：

1. `document.set` 抛 `PathNotFoundException`
2. 各自进 `createPath`
3. `handleObjectPath` 里 `pathExists` 都返回 false
4. **三个线程同时 `document.put("$", "setValueN", new LinkedHashMap<>())`**

典型 **Heisenbug**：目前没炸只因为写次数少、Map 小、时间窗窄。可能表现为：

- 某个 tag 的产出**静默丢失**（`LinkedHashMap` 并发 put 丢键）
- 内部链表 / 树结构损坏 → 后续 `read` 行为不可预测
- 遍历期 `ConcurrentModificationException`

同一批 mock 里的 `and-logic`（L115）、以及任何未来用 `WHEN` 做扇出的真实链路，都在这个面上。

## 4. 影响放大：为什么必须前置于执行记录页

阶段 3 执行记录页要按 `log_level` 采集每节点 IO 快照，落点是：

```java
// DatabusContext.java:462-471
public String snapshotDataSpace(String tag) {
    ...
    Object subtree = readOptional(path);
    return subtree == null ? null : JsonCodec.toJson(subtree);   // ← 遍历子树
}
```

`JsonCodec.toJson` 要**遍历整棵子树**。在 `WHEN` 并行期间调用它，遍历撞上并发结构性修改 → 要么抛 `ConcurrentModificationException`，要么**拍到半构建的树**。

后果不是"程序崩了"（崩了反而好查），是**审计数据不可信**：执行记录里存的 IO 快照缺字段、值错乱，而它恰恰是排障和追责的唯一依据。**所以并发修复必须排在执行记录页之前**，否则等于在一个已知会读到脏数据的地基上盖审计功能。

同理暴露的还有 `toJsonString()`（L311，链路结束时整体序列化）与 `resolve()`（L250，任意节点读参数时）。

## 5. 修复方案：ReentrantReadWriteLock

### 5.1 锁分配

| 锁 | 方法 | 理由 |
| :--- | :--- | :--- |
| **写锁** | `write`(L199) / `save`(L215) / `createPath`(L478) / `registerConnection`(L273) | 会结构性修改树或连接表 |
| **读锁** | `read`(L145) / `read(TypeRef)`(L160) / `readOptional`(L171,L187) / `resolve`(L250) / `resolveMixedPath`(L234) / `exists`(L222) / `snapshotDataSpace`(L462) / `toJsonString`(L311) / `getConnection`(L285) / `allConnections`(L297) | 只遍历，不修改 |

### 5.2 为什么不死锁

关键路径是 `write → createPath → handleObjectPath → PathResolver.pathExists(document, ...)`——**写锁内部要读**。

`ReentrantReadWriteLock` 允许**写锁降级为读锁**（持有写锁的线程可以再取读锁），所以这条链安全。

反向（读锁内升级写锁）**不存在**：所有 `read*` 方法都不会调用 `write`。`ReentrantReadWriteLock` 本身不支持锁升级，若将来出现这种调用会永久阻塞——**这是一条要守住的不变量**，改动时若发现读路径里要写，必须重构调用关系而不是加超时。

### 5.3 ThreadLocal 部分不需要动

已有的四个 `ThreadLocal` 天然线程隔离，与本修复无关，别顺手"统一加锁"：

| 字段 | 行 | 说明 |
| :--- | :--- | :--- |
| `stepSummary` | L65 | 已按线程隔离（注释即写明 WHEN 需要） |
| `loopVarStack` | L84 | 循环变量栈 |
| `pendingLoopVars` | L86 | 待生效循环变量 |
| `loopIndexMap` | L88 | 循环下标表 |

⚠️ 但要注意：`substituteLoopVars`(L435) 在 `write` 里被调用，它读的是 **ThreadLocal**；如果给 `write` 加了写锁，**ThreadLocal 读取仍在锁内**，语义不变（同线程），无副作用。

### 5.4 性能

`ReentrantReadWriteLock` 无竞争时约 20-50ns 量级。一次 `WHEN` 分支里最便宜的节点也是一次 HTTP 调用（毫秒级）或一次 JDBC 往返。**锁开销占比 < 0.001%，不构成反对理由。**

真正会退化的场景是"高并发读 + 极少写"，而本项目的访问模式是**读写交错**（每个节点先 `resolve` 读参数、再 `write` 存产出），读写锁在这种模式下优于 `synchronized`（多个只读节点仍可并行），劣于无锁——但无锁是错的。

## 6. 否决的两个方案

### ❌ 方案 A：根节点换 `ConcurrentHashMap`

三个理由，任一条都足以否决：

1. **只覆盖第一层**。`createPath` 建的嵌套层（`new LinkedHashMap<>()`）和 Jackson 反序列化出的嵌套层，仍然是非线程安全的。`$.a.b.c` 竞争的是 `b` 那一层，不是根
2. **复合操作救不了**。`handleObjectPath` 是 `pathExists`（读）→ `put`（写）的 check-then-act，即使换成 `computeIfAbsent` 也只能保住单层原子性，**跨层建路径**（`createPath` 的 for 循环）本质上不是单 Map 操作
3. **`ArrayList` 那条线完全没覆盖**（§2.4）

### ❌ 方案 B：约束"WHEN 分支只写自己 tag 子树 + 并行前预建顶层键"

**这是约束，不是修复。**

- 预建顶层键确实能消除根 Map 竞争（§2.3 第一行），但**守不住**：Groovy 脚本节点可以 `databusContext.save('$.任意路径', v)` 写任何地方（见 [databus-script-component.md](databus-script-component.md)），组件实现也可能写非自身 tag 的路径（如 `dataPatch` 的 `target` 参数由用户填）
- 靠"约定 + code review"维持的线程安全，在下一次需求变更时就会失效，而且失效方式是静默数据损坏
- 即使约束生效，**读侧仍暴露**：`snapshotDataSpace` 遍历子树时，另一个线程正在写这棵子树的兄弟节点，`JsonCodec.toJson` 若碰到共享父层仍可能 CME

约束可以作为**额外的编码规范**保留（写自己的 tag 子树本来就该是好习惯），但**不能替代锁**。

## 7. "数据是谁改的"——共享可变树答不了，靠审计层

这是本项目与 n8n 数据模型的**根本差异**带来的必答题（详见 [n8n-vs-databus-paradigm.md](n8n-vs-databus-paradigm.md)）：

| | n8n | 本项目 |
| :--- | :--- | :--- |
| 数据形态 | item 数组沿边流动，**每节点输出即不可变快照** | 一棵共享可变树 + tag 命名空间 |
| "这个值是谁写的" | 数据模型自带答案（沿 paired item 回溯） | **树自身回答不了** |
| 归属如何得到 | 免费 | 靠**审计层补**：`snapshotDataSpace(tag)` 在节点结束当下拍快照，执行记录按 tag 存 `detailJson` |

所以本项目是"共享黑板 + 事后拍照"，n8n 是"传送带 + 天然留痕"。**这不是谁优谁劣，是两种范式各自要付的账**：本项目付的是并发安全和审计采集，n8n 付的是跨节点引用冗长和聚合后血缘歧义。

`snapshotDataSpace` 的 Javadoc（L455-457）已经点明"必须在节点执行结束的当下调用"——**加了读锁之后这个"当下"才是可信的**。

## 8. 实施清单

1. `DatabusContext` 加字段 `private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();`
2. 按 §5.1 分配表给每个公开方法包 `lock.readLock().lock()` / `unlock()`（`try-finally`，别用 try-with-resources 之外的写法漏掉释放）
3. `createPath` 是 private、只被 `write` 调用，**不必单独加锁**（已在写锁内），但要加注释说明"调用方必须持有写锁"
4. `getDocument()`（L304）**直接暴露了裸 DocumentContext**——这是绕过锁的后门。改前先 grep 全模块调用点，确认没有组件拿它直接读写；若有，收敛为走 `read/write`
5. 验证边界：`mvnw compile` + IDE 诊断 + 代码审查；`when-parallel` 链路的实测由用户亲跑（项目约定，AI 不自行运行测试）。**建议用户压测方式**：把 `when-parallel` 的三路扩到 20 路 + 循环执行 500 次，观察是否出现丢键或 CME（修复前高概率复现，修复后应零复现）
6. 文档同步：[databus-context-design.md](databus-context-design.md) §6「常见坑」补一条并发说明并链接本篇

## 9. 关键代码位置索引

| 关注点 | 位置 |
| :--- | :--- |
| jayway 配置与裸 LinkedHashMap 根 | [DatabusContext.java](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java) L47-52、L113-118 |
| 连接表（次要暴露面） | 同上 L57、`registerConnection` L273、`getConnection` L285 |
| 写入入口 | 同上 `write` L199、`save` L215 |
| 建路径（竞争核心） | 同上 `createPath` L478、`handleObjectPath` L493、`handleArrayPath` L502 |
| 读取入口 | 同上 `read` L145/L160、`readOptional` L171/L187、`resolve` L250、`resolveMixedPath` L234、`exists` L222 |
| 审计快照 | 同上 `snapshotDataSpace` L462、`toJsonString` L311 |
| ThreadLocal（无需加锁） | 同上 L65、L84、L86、L88 |
| 绕过锁的后门 | 同上 `getDocument` L304 |
| 活体触发链路 | [databus_chain_mock_data.sql:65](../../script/sql/databus_chain_mock_data.sql) `when-parallel` |

## 10. 关联文档

- [databus-context-design.md](databus-context-design.md) —— 上下文机制本身（读写分发、混合路径、动态变量决策）
- [n8n-vs-databus-paradigm.md](n8n-vs-databus-paradigm.md) —— 传送带 vs 黑板两种范式的完整对比（§7 的出处）
- [databus-expression-syntax.md](databus-expression-syntax.md) —— 同批讨论产出的语法契约（`resolve()` 是两篇的共同落点，改动需协调）
- [databus-preview-step-result.md](databus-preview-step-result.md) —— 试运行步骤结果采集，与本篇的审计快照同源
- [databus-script-component.md](databus-script-component.md) —— 脚本节点为何能写任意路径（§6 方案 B 否决理由）
