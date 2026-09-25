# 两种数据范式：n8n 传送带 vs 数据总线共享黑板

> 2026-09-24 三轮对标讨论的沉淀。本篇是**架构论证档**，跨项目复用价值最高（产品定位、方案书、客户沟通都能引）。
> 目的不是"证明我们更好"，是**准确标出两种范式各自要付的账**，并诚实记录被证伪的差异化论证。
> 语法层面的结论已拆到 [databus-expression-syntax.md](databus-expression-syntax.md)，并发层面拆到 [databus-context-concurrency.md](databus-context-concurrency.md)。

## 1. 结论（TL;DR）

| | **n8n** | **本项目** |
| :--- | :--- | :--- |
| 数据模型 | **传送带**：item 数组沿边流动 | **黑板**：一棵共享可变树 + tag 命名空间 |
| 一个节点收到什么 | 上游输出的 **item 数组** `[{json:{...}, binary:{...}}]` | 整棵树（`$`），自己按路径取 |
| 扇出（处理 N 条） | **免费**：节点对每个 item 自动执行一遍 | **要显式声明**：`ITERATOR` / `FOR` 组件 |
| 跨节点取值 | `$('节点名').item.json.x`（点名） | `$.<tag>.xxx`（点名，但树里永远在） |
| 数据归属追溯 | **免费**：paired item 血缘，每节点输出即不可变快照 | **要审计层补**：`snapshotDataSpace(tag)` 事后拍照 |
| 表达式语言 | JavaScript（`{{ }}` 内是真 JS） | JSONPath（将来 `{{ }}` 内接 QLExpress4） |
| 并发安全 | 天然（item 只读，节点产出新建） | **要自己加锁**（见并发篇） |
| 多层嵌套数据 | **痛点**：要 Split Out / 嵌套 Loop / Code 节点 | **优势**：一个路径 `$.groups[$i].users[$j].name` |

**一句话**：n8n 把"处理一批数据"做成默认，把"引用远处数据"做成成本；本项目把"引用任意数据"做成默认，把"处理一批数据"做成显式组件。**两者是同一张账的两面，不存在全面优劣。**

## 2. n8n 的数据流转机制

### 2.1 item 数组

n8n 节点之间流动的不是"一个对象"，而是**一个 item 数组**：

```js
[
  { json: { name: "张三", age: 30 }, binary: {} },
  { json: { name: "李四", age: 25 }, binary: {} }
]
```

- `json` 是结构化数据，`binary` 是文件
- **节点默认对数组里每个 item 执行一次**（扇出免费），输出仍是 item 数组
- 上游给 20 条，下游节点就跑 20 遍——不需要写循环

### 2.2 `$json` 是 JavaScript，不是 JSONPath

这是最容易被误读的一点。`{{ }}` 内是**真 JS 表达式**：

```js
{{ $json.output }}                       // $json === $input.item.json 的简写
{{ $json.user.name.toUpperCase() }}      // 能调 JS 方法
{{ $json.age >= 18 ? '成年' : '未成年' }} // 三元
{{ $items().length }}                    // 拿全部 item
```

所以 n8n 的"表达式引擎"就是 **JS 引擎本身**（Node.js），它不需要选型——语言运行时自带的。这也是本项目无法照搬的：本项目跑在 JVM 上，要 JS 就得引 GraalJS（已在 [databus-expression-syntax.md](databus-expression-syntax.md) §5.3 否决）。

### 2.3 跨节点引用：`$('节点名')`

```js
{{ $('HTTP Request').item.json.code }}     // 当前 item 对应的那个
{{ $('HTTP Request').first().json.code }}  // 明确取第一个
{{ $('HTTP Request').all() }}              // 全部
{{ $jmespath($('Query').all(), "[?age>18]") }}  // JMESPath 辅助函数
```

拿到的是**只读快照**——不能往别的节点命名空间里写。

### 2.4 paired item 血缘（以及它的两个官方错误）

n8n 为每个输出 item 记录了"我是由哪个输入 item 产生的"（paired item）。`$('节点名').item` 之所以能工作，就是靠这条血缘链回溯到"当前 item 对应的那个上游 item"。

**血缘会在两种情况下断**，n8n 官方文档给了具名错误：

| 错误 | 触发场景 |
| :--- | :--- |
| `Multiple matching items for expression` | 中间用了 **Aggregate / Summarize** 把 20 条合成 1 条，之后 `$('X').item` 不知道该对应哪一个 |
| `Info for expressions missing from previous node` | **Code 节点**生成了全新 item 且没声明 `pairedItem`，血缘链断在这里 |

修复方式是显式指定 `.first()` / `.all()`，或插入 Merge 节点重新对齐。

⚠️ **对本项目的启示（也是边界）**：BPM 类链路从头到尾是**单 item**（一次流程实例、一个 BO 记录集），永远不会撞上这两个错误。所以"n8n 血缘麻烦"这个论点**在你们的主场景里不成立**，别拿它去说服客户——真正会撞上的是做批量数据同步的链路。

## 3. 用真实链路逐步对照

以下三条链路取自 [databus_chain_mock_data.sql](../../script/sql/databus_chain_mock_data.sql)，是本项目已落地的真实配置。

### 3.1 `serial-all`（L55）—— 四节点串行

```
httpRequest1 (GET /auth/code)
  → setValue1  ($.setValue1.note = "串行演示")
  → fieldMap1  ($.httpRequest1.response.code → $.fieldMap1.code)
  → response1  (msg = $.httpRequest1.response.msg, dataPath = $.fieldMap1)
```

**本项目怎么读**：`fieldMap1` 直接写 `$.httpRequest1.response.code`——路径即地址，`httpRequest1` 这个 tag 在树里一直存在。

**n8n 怎么写**：`fieldMap` 对应 Set 节点，`{{ $json.response.code }}`（因为上游紧邻，`$json` 就够）；但 `response1` 要同时拿 **HTTP 节点的 msg** 和 **Set 节点的 code**，前者已隔了两个节点，必须写 `$('HTTP Request').item.json.response.msg`。

**差异**：紧邻上游时 n8n 更短（`$json` vs `$.httpRequest1.`）；跨多个节点时两者都要点名，n8n 点的是**节点显示名**（改了名字表达式就断），本项目点的是 **tag**（画布层强制唯一、改名即改引用，但不会静默断）。

### 3.2 `bpm-flow`（L20）—— 多源汇聚，本项目的强项

```
sessionCreate1
  → processStart1  (title = "申请-${$.request.code}")
  → boCreate1      (bindId      = $.processStart1.processInstanceId,
                    sourcePath  = $.request.boList)
  → fileUpload1    (boId         = $.boCreate1.boResults[0].records[0].ID,
                    sourcePath   = $.request.files,
                    processInstId= $.processStart1.processInstanceId)
  → fileDownload1  (boId = $.boCreate1.boResults[0].records[0].ID)
  → taskComplete1  (processInstanceId = $.processStart1.processInstanceId)
```

**看 `fileUpload1`**：它同时需要**三个不同来源**的数据——

1. `$.boCreate1.boResults[0].records[0].ID`（上上个节点的深层产出）
2. `$.request.files`（**链路最初的入参**）
3. `$.processStart1.processInstanceId`（上上上节点的产出）

- **本项目**：三个路径写在同一个参数对象里，完事。树是全局可见的，**"源头"和"上一步"在寻址上没有区别**。
- **n8n**：传送带上流到 `fileUpload` 的 item 只携带 `boCreate` 的输出。要拿另外两个，得写 `$('Process Start').item.json.processInstanceId`，而入参 `$.request.files` 已经被中间节点覆盖过——要么每个节点都小心地把 `request` 透传下去，要么在需要的地方 `$('When Executing Workflow').first().json.request.files` 回溯到起点。**这是传送带范式的固有成本：数据离得越远，取它越贵。**

这条链路是本范式差异最有说服力的实例——企业集成场景里，"当前这一步同时需要入参、中间态和上一步产出"是常态，不是特例。

### 3.3 `iterator-nested`（L100）—— 双层嵌套，本项目的强项

```jsonc
ITERATOR(source: "$.groups")
  └── ITERATOR(source: "$.groups[$i].users")
        └── setValue1(path: "$.iteratorLoop1.last",
                      value: "$.groups[$i].users[$j].name")
```

- **本项目**：两层 `ITERATOR` 组件，`$i` / `$j` 由 ThreadLocal 索引栈管理（`MAX_LOOP_DEPTH = 3`，见 [databus-loop-component.md](databus-loop-component.md)）。**目标路径一行写完**：`$.groups[$i].users[$j].name`。
- **n8n**：要么两个 Loop Over Items 节点嵌套（画布上出现盒子套盒子，可读性下降），要么 `Split Out` 两次把树拍平再处理，要么直接上 Code 节点写双层 `for`。而且**拍平之后再想还原成原来的嵌套结构，还得 Aggregate 回去**——一来一回，血缘也在这过程中容易断（§2.4）。

**这是本项目对企业集成场景最实的一条优势**：上游系统返回的 JSON 很少是"平的一层数组"，多是 `{groups:[{users:[{...}]}]}` 这种。传送带范式要求数据"每次只处理一层"，多层就得靠结构变换节点硬掰；黑板范式里多层只是**路径更长**，不需要任何结构变换。

### 3.4 `when-parallel`（L65）—— 本项目的代价

三路 `setValue` 并行写 `$.setValue1/2/3.out`。

- **n8n**：并行分支各自产出**新的 item 数组**，最后由 Merge 节点合并。数据不可变，**天然无竞争**。
- **本项目**：三个线程往同一棵共享树的根 Map 建顶层键——**真 bug**，已定位，修复方案见 [databus-context-concurrency.md](databus-context-concurrency.md)。

**这就是黑板范式要付的账。** 好处是"任何节点都能看到全部数据"，代价是"任何节点都可能改到同一份数据"。

## 4. 各自的盲区（对称列举，不偏袒）

### n8n 的盲区

| 盲区 | 说明 |
| :--- | :--- |
| **多层嵌套数据** | 见 §3.3，要靠 Split Out / 嵌套 Loop / Code 节点，没有"一个路径直达"的能力 |
| **递归下拉与条件过滤** | JSONPath 的 `$..records`、`[?(@.age>18)]` 在 n8n 里要写 JS 循环，或调 `$jmespath()` 辅助函数（JMESPath 表达力弱于 JSONPath 的递归下降） |
| **远距离取数成本递增** | 见 §3.2，数据离得越远越贵，容易演化成"每个节点都透传一遍"的坏味道 |
| **聚合后血缘歧义** | §2.4 的两个官方错误，批量场景必撞 |
| **节点显示名即引用键** | 改节点名会断表达式（新版有 id 兜底，但历史工作流仍有此坑） |

### 本项目的盲区

| 盲区 | 说明 |
| :--- | :--- |
| **批处理不是默认** | 处理 N 条要显式拖 `ITERATOR`，n8n 是免费的 |
| **并发安全要自己保证** | §3.4，共享可变状态的固有代价 |
| **数据归属靠审计层补** | 树自身答不了"这个值谁写的"（[databus-context-concurrency.md](databus-context-concurrency.md) §7） |
| **表达式能力弱** | 现在只能取值和拼字符串，不能算；n8n 天然有完整 JS。缺口已识别，储备方案见 [databus-expression-syntax.md](databus-expression-syntax.md) §5 |
| **参数冗长** | `$.processStart1.processInstanceId` vs `$json.id`，紧邻上游时明显更长 |
| **树会越长越大** | 每个节点都往里写产出，长链路的树可能很臃肿；n8n 的 item 只带当前需要的 |

## 5. 与 n8n 竞争的诚实边界

### 5.1 已被证伪并撤回的论证

> ❌ **「老系统沉淀的连接器可同进程调用 Java 类，n8n 接它只能走 HTTP OPENAPI」**

**这条是错的，已撤回。** grep 验证：本项目 BPM 侧同样走 OPENAPI HTTP + HmacMD5 签名（`BpmHttpConnector` / `BpmOpenApiSigner` / `BpmHttpConnectionCfg`），**没有用任何 SDK、没有同进程调用**。BPM 端 connector app 暴露的 13 个 HTTP 端点，n8n 用一个 Code 节点算签名后同样能调。

顺带一并撤回：**「JDBC 构成差异」**——n8n 的 SQL 节点很成熟，这不是差异点。

**教训**：差异化论证必须落到"我们代码里真有什么"，不能落到"我们的技术栈理论上能做什么"。

### 5.2 仍然成立的论证（按强度排序）

| # | 论证 | 强度 | 说明 |
| :--- | :--- | :--- | :--- |
| 1 | **边际组件成本** | ★★★★ | 客户已在跑 RuoYi → databus 作为其模块＝新增 **0** 运行时 / **0** 数据库 / **0** 登录体系。引入 n8n＝**+1** Node 运行时 **+1** 库 **+1** 套 SSO 或 iframe 嵌入 **+1** 条升级与漏洞响应线。⚠️ 论的是**边际不是绝对**——RuoYi 本身也要 MySQL + Redis，JVM 也确实比 Node 重，绝对成本我们不占优 |
| 2 | **License 合规** | ★★★★ | n8n 是 **Sustainable Use License，非 OSI 开源**，内嵌进商业产品交付有合规限制，需法务先过。Activepieces（MIT）没这个问题 |
| 3 | **平台内生集成** | ★★★ | 菜单权限、租户、`@EncryptField`、操作审计与 RuoYi **同 JVM 同事务**；n8n 只能靠 API 对拼，且要维护两套用户体系 |
| 4 | **BPM 纵深知识** | ★★★ | 不是「Java vs Node」，是「已经把 BPM 侧 app 写完、部署过、踩过 `@Param("body")` 验签坑并沉淀成规范」（[databus-bpm-endpoint-auth.md](databus-bpm-endpoint-auth.md)）。这份知识 n8n 用户也得自己重新踩一遍 |
| 5 | **国产化 / 等保环境** | ★★★ | Java 中间件在客户批准清单内，Node 运行时常常不在 |
| 6 | **SAP RFC 等重协议** | ★★ | SAP JCo 主战场在 Java。**但本项目目前也没有这个连接器**，是未来可能的差异，不能当现有优势讲 |

### 5.3 两种"高可靠"形态（澄清一个常被混淆的点）

| | n8n（云原生多组件） | 本项目（单进程企业） |
| :--- | :--- | :--- |
| 可靠性来自 | 无状态 worker 横向扩容 + Redis 队列 + 共享 Postgres + LB | 单 JVM 内 LiteFlow 执行 + MySQL 执行记录 + 失败重跑 |
| 依赖什么 | **基础设施拓扑**（要多台机器、要 K8s 或至少 LB + 队列） | **进程内语义**（`CATCH` / retry / 审计快照 / 事务） |
| 故障模型 | "挂一个还有九个" | "挂了能从记录里查清楚、能重跑" |
| 谁运维得动 | 有 SRE 团队的 | 只有 2 台服务器、DBA 兼运维的 |

**这不是谁敢不敢上高可靠，是两种不同的可靠机制。** 本项目客户的环境没有 K8s、没有 SRE，第二种才是他们真能运维的形态。

### 5.4 结论

> **护城河不在技术栈，在「已经在房子里」+「BPM 纵深」+「私有化交付形态」。**

配套的行动准则（2026-09-24 拍板）：

- **凡通用 iPaaS 能力一律抄，不自研**：表达式引擎、批处理范式、调试 UI、字段补全、求值预览
- **凡连接真实企业系统的资产一律深耕**：BPM 连接器、执行审计、发布治理、企业内私有化交付

**该被 n8n 淘汰的只有一个念头：什么表达力都自己造。**

## 6. 对标产品地图

调研 backlog（不占开发排期，穿插进行，每项一段结论写回 `.trae/roadmap.md` §四）：

| 优先级 | 产品 | 协议 / 栈 | 看什么 | 与本项目关系 |
| :--- | :--- | :--- | :--- | :--- |
| **P0** | **AWS Step Functions** | 商业 / JSON | 单 JSON 报文 + JSONPath 寻址 + 字段名 `.$` 后缀 + `States.Format` | **与本项目数据模型最接近的商用产品**，是"这条路走得通"的最强背书。后缀方案已在 [databus-expression-syntax.md](databus-expression-syntax.md) §3 评估并否决 |
| **P0** | **Camunda 7 / 8** | Apache(7) / Java | **FEEL 类型化表达式**、Connector 模板机制 | **哲学最接近**，Java 同构，表达式与连接器的工程范本。会在"客户本来就要上 BPM"的单子里正面碰上 |
| P1 | Apache Camel | Apache / Java | **EIP 模式目录**、消息模型 | 领域**词汇表**，事实标准。将来可只借个别组件模式，不必换引擎 |
| P1 | Temporal | MIT / Go+SDK | 持久化执行、重试 / Saga / 定时器语义 | 可靠性设计**教科书**。代码优先、无低代码界面，**是老师不是对手** |
| P2 | Node-RED | Apache / Node | 私有化部署形态、节点扩展机制 | IBM 系老牌，边缘自动化与私有化里很常见。**小私有化单的实际对手** |
| P2 | Activepieces | **MIT** / TS | 协议对照、社区扩展机制 | 年轻，MIT 协议无 n8n 的合规限制 |
| P2 | Windmill | AGPL / Rust | 脚本优先的开发者向形态 | 开发者向，交集小 |
| P2 | Microsoft Power Automate | 商业 | 本地网关 + RPA + 企业身份集成 | **预算充足大企业客户最可能掏出的对手** |
| P3 | MuleSoft DataWeave | 商业极贵 | DataWeave 表达式语言（业界天花板） | 看概念就行，**不会出现在本项目价位的牌桌上** |
| P3 | Spring Integration | Apache / Java | Spring 原生 EIP | 开发者向，知道即可 |
| P3 | Airflow / Dagster / Kestra | Apache 等 | 数据管道编排 | 与本项目**交集不大** |

**精读三个**：Camel 的 EIP 模式目录（建立专业词汇，免费）、Camunda 的 FEEL 与 Connector 设计（Java 同构的范本）、n8n 的交互体验（已在做）。

**国内产品**（宜搭、简道云、影刀 RPA）：交集在"自动化"三个字，但编排深度和系统对接不在同一层，不构成直接竞争。

## 7. 参考资料

- n8n 数据流与表达式：`https://docs.n8n.io/code/builtin/data/`、`https://docs.n8n.io/code-examples/expressions/`
- n8n item linking 与两个官方错误：`https://docs.n8n.io/data/data-mapping/data-item-linking/`、`.../item-linking-errors/`
- AWS Step Functions 输入输出过滤：`https://docs.aws.amazon.com/step-functions/latest/dg/concepts-input-output-filtering.html`
- Camunda FEEL：`https://docs.camunda.org/manual/latest/reference/feel/`
- Temporal：`https://docs.temporal.io/`
- Apache Camel EIP：`https://camel.apache.org/components/latest/eips/enterprise-integration-patterns.html`
- LiteFlow 脚本引擎（v2.16.1，8 语言 11 坐标）：项目内 skill `how2useliteflow` 的 `references/scripts.md`
  ⚠️ 官网 `https://liteflow.cc/pages/38c781/` 是 **v2.11.X 旧文档**（写着 7 种语言，页面底部 2023-08-28），不要以它为准

## 8. 关联文档

- [databus-expression-syntax.md](databus-expression-syntax.md) —— `{{ }}` 语法契约与引擎演进（本篇 §2.2、§4 的落地结论）
- [databus-context-concurrency.md](databus-context-concurrency.md) —— 黑板范式的并发代价（本篇 §3.4、§4 的落地结论）
- [databus-context-design.md](databus-context-design.md) —— DatabusContext 机制本身
- [databus-loop-component.md](databus-loop-component.md) —— `$i/$j/$k` 索引栈（本篇 §3.3 的实现）
- [databus-formula-engine-research.md](databus-formula-engine-research.md) —— 三层能力模型与引擎横评
- [databus-bpm-endpoint-auth.md](databus-bpm-endpoint-auth.md) —— §5.2 第 4 条"BPM 纵深知识"的实证
