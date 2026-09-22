# 数据总线链路与执行记录管理设计

> 所属模块：ruoyi-databus
> 更新日期：2026-09-22
> 状态：设计已拍板（含 Rule-DB 前置接入 + 执行记录采集改用 PostProcessNodeExecuteLifeCycle+Slot 修正），待实施（阶段 3 链路管理页 + Rule-DB 接入配套）

## 1. 文档目的

阶段 3「前端配置管理页」开工前的设计决策档。回答三个问题：

- databus_chain 表要怎么扩展、发布/下线端点契约
- 执行记录两表要落什么、记录档位怎么分、重跑怎么复用
- 这些设计为什么走轻量路线（方向依据见定位档）

系统是什么、能做什么、与普通工作流的本质差距、我们朝哪个方向做——这些**方向性决策**见 [databus-overview.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-overview.md)。本档只管链路管理与执行记录的功能设计，方向服从定位档。

## 2. 系统定位与方向（见定位档）

一句话提要：数据总线是**数据加工管道编排平台**，链路是「配置」不是「程序」，走轻量设计路线（单表 + status，不做 Camunda 式重型版本管理）。

这个定位直接决定本文后续设计：

- **链路管理**走轻量单表（§3）：没有长期实例 → 下线不需要挂起逻辑；链路是配置 → 草稿/发布分离但用单表 status 即可
- **执行记录**走「节点 IO 全落 json + 档位 select + 重跑复用 preview-run」（§4）：审计 + 重跑是两个核心目的，档位让用户按需权衡存储成本

详见 [databus-overview.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-overview.md) §3「与同类系统的本质差距」、§4「我们朝哪个方向做」。

## 3. 链路管理设计

### 3.1 模式选择：轻量单表

- 单表 + status 字段区分草稿/发布/下线
- 草稿与发布共用一个 content（el_expression + canvas_data），发布即把当前编排固化为运行版本，改了就得重发才生效
- 双列隔离（draft_json + published_json）留待将来「发布后还想偷偷改草稿不影响线上」强需求出现再加
- 版本号 version 每次发布递增，但**不存历史快照**（MVP 拍板不做版本历史），version 只是计数器，不是版本管理

> **业务元数据层与执行引擎层分离**：`databus_chain` 表只承担业务元数据（草稿/发布/下线三态、log_level 配置、画布数据、版本计数器），**EL 不再作为运行期权威源**——发布后 EL 推到 LiteFlow Rule-DB 的 `lf_chain` 表，由框架接管缓存/一致性/版本/last-good 兜底（见 §3.6）。这是「轻量优先」针对业务元数据层的边界外内容，详见定位档 §4.1 与 §4.5。

### 3.2 databus_chain 表结构（现状 + 扩展）

现有字段（已建，实体类 DatabusChain.java）：

| 字段 | 类型 | 说明 |
|:---|:---|:---|
| id | bigint | 主键 |
| chain_code | varchar | 链路编码 |
| chain_name | varchar | 链路名称 |
| version | int | 版本号（每次发布递增，草稿恒为 1） |
| status | varchar | 状态（0草稿 1已发布 2已下线）← 已是三态，符合轻量模式 |
| el_expression | text | LiteFlow EL（后端从 CmpProperty 权威生成） |
| canvas_data | text | 画布 JSON（VueFlow nodes/edges 序列化） |
| del_flag | char | 删除标志 |
| remark | varchar | 备注 |
| + BaseEntity | — | create_by/create_time/update_by/update_time/tenant_id |

**新增字段**（本阶段补）：

| 字段 | 类型 | 默认 | 说明 |
|:---|:---|:---|:---|
| log_level | varchar(16) | BASIC | 执行记录档位：OFF / BASIC / FULL |

> Rule-DB 接入引入的 `lf_chain` / `lf_script` / `lf_change_log` / `lf_change_lock` 四表由 LiteFlow 框架管理（DDL 见 `liteflow-rule-db-sql/src/main/resources/sql/ddl-mysql.sql`，或开 `liteflow.rule-db.sql.auto-init-table=true` 启动自动建表），**不计入业务表范畴**——databus_chain 与 lf_* 职责分离，见 §3.6。

### 3.3 status 三态语义

| 值 | 含义 | 可执行 | 可编辑 |
|:---|:---|:---:|:---:|
| 0 | 草稿 DRAFT | ❌ | ✅ |
| 1 | 已发布 PUBLISHED | ✅ | ❌（需重新发布） |
| 2 | 已下线 OFFLINE | ❌ | ✅（可重新发布） |

- 发布 = `status` 0→1 + `version`+1 + **同时调 `RulePublisherFactory.publishChain` 把 EL 推到 `lf_chain` 表**（见 §3.6）
- 下线 = `status` 1→2 + 调 `RulePublisher.removeChain` 从 `lf_chain` 删除（或置 `enable=0`）
- 重新发布 = `status` 2→1 + `version`+1 + `publishChain` 覆盖推送（带 `expectedVersion` 乐观锁）

下线后定义保留在 `databus_chain` 表，不物理删除；`lf_chain` 表的 EL 视场景保留（便于快速重新发布）或 remove。

### 3.4 发布/下线端点契约（待新增）

DatabusChainController 已有 list/getInfo/add/edit/remove，缺 publish/offline：

- `POST /databus/chain/publish/{id}` → `status`→1, `version`+1, 同时 `RulePublisher.publishChain(PublishChainRequest{chainId, el, expectedVersion})` 推 EL 到 `lf_chain`
- `POST /databus/chain/offline/{id}` → `status`→2, `RulePublisher.removeChain` 从 `lf_chain` 移除（或 `enable=0`）

权限 key 复用 `databus:editor:edit` 或新增 `databus:chain:publish` / `databus:chain:offline`，实施时定。

> `RulePublisher` 是 `AutoCloseable`，推荐 try-with-resources；与 `lf_chain` 已有版本不一致时用 `expectedVersion` CAS 控制（编辑表单场景传当前 version，新建传 0，无条件 UPSERT 不传）。详见 [rule-db.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/rule-db.md) §5。

### 3.5 调用入口与发布语义

当前 MVP 仅 HTTP 同步调用 + 试运行，发布 = 该 chainId 可被执行接口接受调用。**执行入口用 `flowExecutor.execute2Resp(chainId, param, DatabusContext.class)`，框架从 Rule-DB 加载 EL（首次回源拉取并编译，Caffeine 缓存命中后热路径零远程调用）**，不再走动态建链（`LiteFlowChainELBuilder`）。试运行的 `PREVIEW_CHAIN_ID-<executionId>` 临时链路保持 `LiteFlowChainELBuilder` 动态建链（不入 Rule-DB），避免污染正式链路清单。

将来定时/MQ 触发进来，发布会隐含「启用触发器」语义，那是后面阶段的事，现在三态足够覆盖。

### 3.6 Rule-DB 接入（发布底层）

Rule-DB 是 LiteFlow v2.16.1 统一规则数据库，作为执行引擎层的高可用基础设施前置接入（定位档 §4.5）。**后端选 MySQL**（复用已有库，无新基础设施），引入 `liteflow-rule-db-sql` 依赖，配置走 `liteflow.rule-db.*`（与容器已有 `DataSource` bean 自动复用，零连接配置）。

**职责分离**：

| 表 | 归属 | 职责 |
|:---|:---|:---|
| `databus_chain` | 业务元数据层 | 草稿/发布/下线三态、log_level 配置、画布数据、版本计数器、备注等业务字段 |
| `lf_chain` / `lf_script` | 执行引擎层（Rule-DB） | 发布后的 EL 与脚本（权威源）、version + content_md5 指纹、enable 状态 |
| `lf_change_log` / `lf_change_lock` | 执行引擎层 | 变更日志（轮询/对账收敛依据）+ 顺序锁（保证 seq 分配与事务提交顺序一致） |

**框架接管的能力（不自建）**：
- 多节点最终一致：seq 轮询（默认 3s）+ 60s 周期对账兜底，秒级窗口收敛
- last-good 兜底：已成功激活的链路在存储抖动或新版编译失败时继续服务（核心可用性属性）
- 重启零感知：JVM 常驻规则清单 + 影子 Chain/Node 索引，EL/编译产物进 Caffeine 有界缓存懒加载
- 乐观锁版本控制：`expectedVersion` CAS（null=UPSERT, 0=新建, N=CAS 更新）
- 变更通知：SQL 用 seq 轮询，存储恢复后自动续订

**配置要点**：
- `liteflow.rule-db.sql.url` 留空 → 自动复用容器 `DataSource` bean（推荐，连接池化）
- `liteflow.rule-db.application-name` 必须配，多应用共库场景作隔离维度（发布侧与执行侧必须一致）
- `liteflow.rule-db.sql.auto-init-table=true` 启动时自动建四张表（首次部署用，生产可手动建表）
- `liteflow.rule-source` 必须移除（与 rule-db 互斥，同配启动报错）
- `parseMode` / `enableMonitorFile` / `chainCache*` 配置失效（rule-db 接管，建议删掉）

**DatabusExecutor 改造**（核心代码改动，详见 §5 落地步骤）：
- 当前：动态建链 `LiteFlowChainELBuilder.createChain().setChainId(...).setEL(原始EL).build()` + `execute2Resp`（绕开 `execute2RespWithEL` 的 normalize bug，详见 [liteflow-el-normalize-bug.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/liteflow-el-normalize-bug.md)）
- 改造后：正式链路用 `execute2Resp(chainId, ...)`，框架从 Rule-DB 加载 EL；试运行链路保持 `LiteFlowChainELBuilder` 动态建链（不入 Rule-DB）

**v1 已知边界**（来自 [rule-db.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/rule-db.md) §10）：
- 最终一致、秒级窗口，**不满足全集群同一时刻切版**（窗口内不同节点可能短暂跑不同版本；进行中的执行持旧引用跑完）
- v1 不提供管理 UI（管理后台发布 API 已够，UI 由我们的链路管理页承担）
- 启动时存储不可用直接抛异常、启动失败（不降级空规则跑起来）
- 手改 `lf_chain` 必须同时 `version+1` + `content_md5=MD5(el_data)`，否则永不生效（推荐走 `RulePublisher` API）

## 4. 执行记录设计

### 4.1 设计动机

两个核心目的，缺一不可：

1. **审计**：查看某次调用里每一步的输入、输出、过程记录（哪步成功失败、耗时、走了哪个分支、循环跑了几轮）
2. **重跑**：把某条失败记录的入参存下来，不依赖外部调用，自己用原数据模拟重跑

### 4.2 两表结构

**databus_execution（执行级）**：

| 字段 | 说明 |
|:---|:---|
| id | 主键（executionId） |
| chain_id | 链路 id |
| chain_code | 链路编码（冗余便于查询） |
| request_data | 入参 JSON（文档根） |
| response_data | 最终输出 JSON |
| status | 执行状态（成功/失败/进行中） |
| error_msg | 错误信息 |
| start_time / end_time | 起止时间 |
| duration | 耗时 ms |
| + BaseEntity | — |

**databus_execution_node（节点级）**：

| 字段 | 来源（PostProcessNodeExecuteLifeCycle + Slot） | 说明 |
|:---|:---|:---|
| id | — | 主键 |
| execution_id | PostProcessFlowExecuteLifeCycle 上下文（生成 executionId 关联） | 关联 execution |
| tag | `cmp.getTag()`（NodeComponent 的 tag，即组件实例 dataSpace 名） | EL 里 tag |
| node_type | `cmp.getNodeId()`（注册类型名，实例唯一性靠 tag 区分） | 组件注册名（httpRequest/condition/...） |
| input_json | `slot.getInput(nodeId)`（从 Slot 取节点输入快照） | 节点输入 JSON |
| output_json | `slot.getOutput(nodeId)`（从 Slot 取节点输出快照） | 节点输出 JSON |
| status | `e == null ? SUCCESS : FAILED`（after 钩子第三参 Exception 判断） | 节点状态 |
| error_msg | `e != null ? e.getMessage() : null`（after 钩子异常参数） | 错误信息 |
| start_time | `System.currentTimeMillis() - timeSpent`（after 钩子第二参反推） | 开始时间 |
| end_time | `System.currentTimeMillis()`（after 触发时刻） | 结束时间 |
| duration | `timeSpent`（after 钩子第二参，毫秒） | 耗时 |
| branch_info | `slot.getIfResult(tag)` / `slot.getSwitchResult(tag)` / 循环轮次从 `slot.getExecuteSteps()` 的 `CmpStep` 取 | 分支标记（IF 真假/SWITCH 命中 case/循环轮次） |
| + BaseEntity | — | — |

> **采集实现**：`PostProcessNodeExecuteLifeCycle.postProcessAfterNodeExecute(NodeComponent cmp, long timeSpent, Exception e)` 在 `NodeComponent.execute()` 的 `finally` 块中触发（源码 `liteflow-core/.../core/NodeComponent.java:184-188`），无论成功异常都触发，能拿到配对的耗时/异常信息。配合 `PostProcessFlowExecuteLifeCycle.postProcessAfterFlowExecute(chainId, Slot slot)` 在整次流程结束后一次性 dump 执行级（databus_execution，从 Slot 取 requestData/responseData/exception/executeSteps/chainId 等）。详见 [lifecycle.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/lifecycle.md) §三.3 与 §四。

### 4.3 节点 IO 全落 json（已拍板，采集方式改用 LiteFlow 框架钩子）

所有节点输入输出均落 json 字段，**采集改用 LiteFlow v2.16.1 框架级钩子 `PostProcessNodeExecuteLifeCycle` + `Slot`，废弃自建 `NodeStepResultCollector`**（重复造轮子，详见 §4.2 字段对照表）。

- **节点级（FULL 档触发）**：`PostProcessNodeExecuteLifeCycle.postProcessAfterNodeExecute(NodeComponent, long, Exception)` 每个节点执行后触发（finally 中，成功异常都触发），从 NodeComponent 取 tag/nodeId，从 Slot 取 input/output，从参数取 timeSpent/Exception
- **执行级（BASIC/FULL 档触发，OFF 不触发）**：`PostProcessFlowExecuteLifeCycle.postProcessAfterFlowExecute(chainId, Slot)` 整次流程结束后一次性 dump（从 Slot 取 requestData/responseData/exception/executeSteps/chainId）

**档位与钩子的配合**：钩子入口读 `databus_chain.log_level`，OFF 直接 return；BASIC 只触发执行级写入；FULL 执行级 + 节点级都写入。`NodeStepResultCollector` 及相关 `StepResultPayload` / `DatabusExecutionResult VO.summary` 在 Rule-DB 接入完成后清理（保留到原 preview-run 通道稳定切换到 Rule-DB 加载路径后再废弃）。

执行记录页详情抽屉按节点展示 IO、状态、耗时、分支。

### 4.4 记录档位（select 多档）

挂在 databus_chain.log_level，挂 RuoYi 字典 `databus_log_level`，前端用 `<el-select>` 字典渲染：

| 档位 | 落什么 | 能审计 | 能重跑 | 场景 |
|:---|:---|:---:|:---:|:---|
| OFF 关闭 | 完全不落库 | ❌ | ❌ | 极致高吞吐、调用方自记日志 |
| BASIC 基础（默认） | 执行级（入参/出参/状态/耗时/错误） | ✅ | ✅ | 大多数链路 |
| FULL 完整 | 执行级 + 节点级每步 IO | ✅✅ | ✅ | 关键链路 / 调试期 |

默认 BASIC——能审计能重跑，又不背节点明细存储成本。

**ERROR_ONLY（成功不记失败记全量）暂不做**：实现成本高（执行中要暂存全部明细最后判断 flush 还是丢弃，或始终落库成功后再删），等强场景出现再加。

### 4.5 重跑机制

重跑 = 试运行的特例。试运行是用户手填 JSON，重跑是从历史 execution 取 request_data 再跑一次。**复用 preview-run 执行通道**，只是入参来源不同。新增「用 executionId 重跑」入口即可，不造新通道。

## 5. 落地步骤

> **重大调整（2026-09-22）**：Rule-DB 接入前置到第 1 步与链路管理页配套做（发布动作依赖 `lf_chain` 表存在），原"5 小步"扩为"6 小步"。执行记录采集方案同步改用 LiteFlow 框架钩子，废弃 NodeStepResultCollector。

### 5.1 链路管理页 + Rule-DB 接入（6 小步，阶段 3 主线）

1. **后端 SQL + Rule-DB 配置**：
   - databus_chain SQL 沉淀 script/sql/databus_chain.sql（含 log_level 新增字段 + 完整 DDL）
   - databus_component SQL 沉淀（如缺）
   - sys_menu 菜单 SQL + databus_log_level 字典数据 SQL（OFF/BASIC/FULL）
   - pom 加 `liteflow-rule-db-sql:2.16.1.3` 依赖
   - application.yml 配置 `liteflow.rule-db.*`（url 留空复用 DataSource、application-name 必填、auto-init-table=true 首次建表）+ 移除 `liteflow.rule-source`（互斥）
2. **后端 Rule-DB 接入 + DatabusExecutor 改造**：
   - DatabusExecutor 正式链路改为 `execute2Resp(chainId, param, DatabusContext.class)`，框架从 Rule-DB 加载；试运行链路保持 `LiteFlowChainELBuilder` 动态建链（不入 Rule-DB）
   - 发布端点 `POST /databus/chain/publish/{id}`：status→1 + version+1 + `RulePublisher.publishChain`（try-with-resources，带 expectedVersion CAS）
   - 下线端点 `POST /databus/chain/offline/{id}`：status→2 + `RulePublisher.removeChain`
3. **前端**：src/api/databus/chain/index.ts API 封装；src/views/databus/chain/index.vue 列表骨架（el-table + 搜索 + 状态过滤 + 分页）
4. **前端**：新增/编辑弹窗（基础信息表单 + 跳编辑器编排画布）
5. **前端**：发布/下线/删除操作 + 确认弹窗
6. **前端**：编辑器跳转契约（chainId 加载 + 保存回跳）

### 5.2 执行记录页（链路页完成后拆步）

- 后端补 databus_execution + databus_execution_node 两表 + Entity/Mapper/Service/Controller
- **采集实现：`PostProcessNodeExecuteLifeCycle` + `PostProcessFlowExecuteLifeCycle` 两个 Spring Bean（@Component 自动扫描），按 log_level 档位控制落库粒度（OFF 不入钩子/直接 return；BASIC 只执行级；FULL 执行级+节点级）**
- **废弃 NodeStepResultCollector + StepResultPayload**（切换稳定后清理，preview-run 通道保留至切换完成）
- 前端列表 + 详情抽屉（瀑布图 + 节点 IO）
- 重跑入口（从 execution 取 request_data 走 preview-run 通道）

## 6. 关键参考

- [databus-overview.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-overview.md) — 系统定位与方向（顶层决策档，本档方向依据；§4.1 轻量优先边界 + §4.5 Rule-DB 接入决策）
- [databus-context-design.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-context-design.md) — DatabusContext 数据空间模型（$.dataSpace 共享黑板）
- [databus-preview-step-result.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-preview-step-result.md) — NodeStepResultCollector 采集钩子（**已废弃**，改用 PostProcessNodeExecuteLifeCycle+Slot，§4.3；切换稳定前保留 preview-run 通道）
- [lifecycle.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/lifecycle.md) — LiteFlow 框架级生命周期（§三.3 PostProcessNodeExecuteLifeCycle + §四 Slot API，执行记录采集新方案依据）
- [rule-db.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/rule-db.md) — LiteFlow Rule-DB 统一规则数据库（§3 配置 + §5 发布 API + §6 四张表 + §7 一致性 + §9 降级语义，§3.6 Rule-DB 接入依据）
- [liteflow-el-normalize-bug.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/liteflow-el-normalize-bug.md) — execute2RespWithEL normalize bug 记录（DatabusExecutor 改造后正式链路绕过此 bug 的依据）
- [roadmap.md](file:///e:/01.code/databus-meta/.trae/roadmap.md) — 阶段 3 交付物
- [work-state.md](file:///e:/01.code/databus-meta/.trae/work-state.md) — 当前阶段任务清单
