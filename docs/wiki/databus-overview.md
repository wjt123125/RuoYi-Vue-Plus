# 数据总线：定位与方向

> 项目顶层决策档 | 更新日期：2026-09-22
> 性质：方向性决策，非功能设计。所有具体功能设计档应与本文方向一致，冲突时以本文为准。

## 1. 一句话定位

数据总线是一个**数据加工管道编排平台**。把 HTTP 请求、DB 查询、BPM 操作、文件处理、AI 推理等原子操作，按串行/并行/条件/循环编排成一条链路，发布后供调用——传 JSON 进，跑完出 JSON。

## 2. 能做什么

### 2.1 能力清单

- **原子操作组件**：HTTP 请求、BPM 的 BO 增删改/任务/附件、RDS 查询、文件上传下载、字段映射、赋值、脚本（Groovy）、AI 推理
- **流程控制**：串行 THEN、并行 WHEN、条件 IF、选择 SWITCH、循环 FOR/ITERATOR/WHILE、异常捕获、子链路嵌套
- **共享数据空间**：DatabusContext 持 jayway DocumentContext，所有节点读写同一块 JSON 黑板，组件产出挂 `$.<dataSpace>.xxx`
- **jsonpath 内联引用**：组件参数值可直接写 jsonpath，跨节点取值、混合路径拼接
- **可视化编排**：Vue3 + VueFlow 编辑器，拖拽搭管道、实时预览 EL
- **试运行 + 执行记录**：弹窗输 JSON 看结果；执行记录可审计每步 IO、可重跑
- **监控**：调用次数、成功率、耗时、慢节点、错误聚合（roadmap 阶段 5）

### 2.2 典型场景

> 调一个外部 HTTP 接口拿数据 → 按字段挑出来转格式 → 查 RDS 补点数据 → 按条件分叉 → 塞给 BPM 建业务对象 → 中间可能还要循环遍历、文件上传下载、AI 推理。

业务人员或开发在编辑器里拖拽搭好一条管道，发布后供三种入口调用：HTTP 同步调用、定时触发、MQ 消息驱动。

## 3. 与同类系统的本质差距

普通工作流（n8n / Camunda）和数据总线看着像，骨子里是三件事。这些差距直接决定我们走轻量设计路线。

### 3.1 数据怎么流动：消息传递 vs 共享黑板

- **普通工作流**：数据在节点间「传」——上一步 output 接下一步 input，连线 = 数据通道
- **数据总线**：所有节点共享一块 JSON 黑板（DatabusContext），组件产出写 `$.<dataSpace>.xxx`，下游用 jsonpath 读 `$.httpRequest1.response.data[0].id`。画布连线只表达**执行顺序和分支结构**，不是数据通道

### 3.2 链路是「函数」还是「程序」：请求-响应 vs 长流程

- **普通工作流**：流程定义是「程序」，被反复实例化，每个实例有状态、历史、可能跑数天（审批流典型）
- **数据总线**：链路是「函数」，一次执行 = 入参 JSON → 跑一串组件 → 出 JSON。同步请求-响应为主，跑完即结束

### 3.3 参数能直接引用上下文：jsonpath 内联

- 组件参数值可直接写 jsonpath 引用：HTTP 组件 url 拼 `$.input.baseUrl`，BPM 组件字段从 `$.httpRequest1.response.data[0].id` 取
- 整个 JSON 文档就是数据空间，路径就是引用语法。普通工作流参数槽是静态值或独立表达式引擎，没这个概念

### 3.4 差距如何决定设计方向

- 没有「进行中的实例」 → 下线不需要挂起逻辑，status 改 OFFLINE 即可
- 没有「多版本并行跑」 → 版本历史不是刚需，数据加工管道改完就发
- 链路是「配置」不是「程序」 → 草稿/发布分离仍需要，但分离方式可简单到单表 + status

所以轻量模式（单表 + status）对数据总线不是「将就」，是「正好匹配」。Camunda 那套重型版本管理是给「长期运行的程序」设计的，数据总线不背那个包袱。

## 4. 我们朝哪个方向做（方向决策）

这是本文档的核心——四个方向决策，所有功能设计都要服从：

### 4.1 轻量优先（有边界）

轻量是**针对业务元数据层**——能用单表不双表、能用 status 不搞版本历史、能复用执行通道不造新通道。每个「要不要做更复杂」的念头，先问「本质差距是否真的需要它」。数据总线没有长期实例、没有多版本并行，这些 Camunda 式的复杂度对我们是负担不是资产。

**但不针对执行引擎层的高可用基础设施**：Rule-DB（LiteFlow 统一规则数据库）作为发布底层前置接入，理由是产品面向 C/B 端——last-good 兜底、多节点一致性、重启零感知是产品性质决定的基本要求，不算"过度设计"，不能等"信号出现再加"。详见 §4.5 与 [databus-chain-execution-design.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-chain-execution-design.md) §5。

### 4.2 配置优先，不是程序

链路是「数据加工的配置」，不是「业务流程程序」。发布即固化当前编排为运行版本，改了就得重发才生效。不追求「发布后还能偷偷改草稿不影响线上」这种重型场景——真出现强需求再加双列隔离，不预先背。

### 4.3 不过度设计

双列隔离、版本历史、ERROR_ONLY 记录档位、自研公式引擎——这些都等**强场景信号出现再加**。信号没来就硬忍，不预先造。表达式语法这种对外契约「晚定比早定便宜」。

### 4.4 原子操作丰富化 > 引擎复杂化

把力气花在**组件物料**（HTTP / BPM / RDS / 文件 / AI）和**编排能力**（分支 / 并行 / 循环 / 子链路）上，而不是再造工作流引擎那套重型版本管理、流程实例、挂起激活。引擎层 LiteFlow 已经够用，我们的增量价值在物料和编排，不在引擎。

### 4.5 执行引擎层接入 Rule-DB（产品要求，非过度设计）

Rule-DB（LiteFlow v2.16.1 统一规则数据库）是引擎层的高可用基础设施：链路定义统一存储 + JVM 有界缓存懒加载 + 多节点最终一致 + last-good 兜底 + 乐观锁版本控制 + 重启零感知。**产品面向 C/B 端，这些能力是产品性质决定的基本要求**，不是"信号出现再加"的可选项，因此作为发布底层前置接入（与链路管理页发布动作配套做）。

- 后端选 MySQL（复用已有库，无新基础设施）
- 发布动作 = `databus_chain.status` 改 PUBLISHED + 同时调 `RulePublisherFactory.publishChain` 把 EL 推到 `lf_chain` 表
- DatabusExecutor 从"动态建链（`LiteFlowChainELBuilder`）"改为"按 chainId 直接执行（`execute2Resp(chainId)`，框架从 Rule-DB 加载）"
- 不自建链路缓存/一致性/版本管理，交给 LiteFlow 框架

详见 [databus-chain-execution-design.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-chain-execution-design.md) §3.6 与 §5 落地步骤。

## 5. 边界（不做什么）

> 边界清单针对**业务元数据层**（链路版本/双列隔离/挂起激活等）。执行引擎层的高可用基础设施（Rule-DB）不算边界外，已在 §4.5 明确前置接入。

- **不做审批流**：Warm-Flow 设计器是审批流专用，roadmap 已明确排除
- **不做长期运行实例**：请求-响应为主，不跑数天的长流程
- **不做重型版本历史**：MVP 不做版本快照，version 只是递增计数器
- **不自研公式引擎**：已调研拍板（见 databus-formula-engine-research.md），旧 `@公式` 不 1:1 迁移，能力缺口暂由 Groovy 脚本兜
- **不做假组件/桩组件**：直接生成真实组件，但不要求一次完善

## 6. 关联文档

- [roadmap.md](file:///e:/01.code/databus-meta/.trae/roadmap.md) — 六阶段实施路线，本方向档的执行计划
- [databus-context-design.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-context-design.md) — 共享黑板模型（DatabusContext）
- [databus-formula-engine-research.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-formula-engine-research.md) — 公式引擎不自研的论证
- [databus-chain-execution-design.md](file:///e:/01.code/RuoYi-Vue-Plus/docs/wiki/databus-chain-execution-design.md) — 链路管理/执行记录设计（本文方向的落地应用）
- [lifecycle.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/lifecycle.md) — LiteFlow 框架级生命周期（PostProcessNodeExecuteLifeCycle 节点执行钩子，执行记录采集基础）
- [rule-db.md](file:///e:/01.code/databus-meta/.trae/skills/how2useliteflow/references/rule-db.md) — LiteFlow Rule-DB 统一规则数据库（七后端/发布 API/一致性/降级，§4.5 接入依据）
