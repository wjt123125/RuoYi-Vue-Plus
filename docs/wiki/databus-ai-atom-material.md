# AI 原子物料生产线（愿景、终局架构与三步路线）

> 状态：2026-10-02 讨论确认的**设计档**，纯结论，尚未进入实施。
> 第一步「schema-driven 配置表单」为已拍板的当下开工项；第二、三步为终局方向，实施前各自还需一次细化设计。
> 索引：roadmap《AI 原子物料生产线与 schema-driven（2026-10-02 讨论确认）》；本文为该议题正本。

---

## 1. 愿景与用户态度（立项之本，不得淡化）

### 1.1 目标场景

ERP/MES 厂商给出接口文档，用户用大白话描述要做什么（比如「把 MES 的工单数据同步到 ERP，再按状态回写」），AI 自主完成：

1. 分析这次集成需要哪些原子能力；
2. 盘点平台现有物料（示例：8 个已有的就够）；
3. 补缺——为真正缺失的能力编写**纯叶子原子**（示例：缺 2 个就写 2 个脚本原子）；
4. 自己串接，生成 EL / nodeTree，前端直接渲染成画布；
5. 用户只做两件事：**审核 + 点试运行**。

商业模式目标：集成交付从「卖人天」变为「AI 生产、人审核」。

### 1.2 用户拍板的态度与规则（原话留存）

- 「AI 写的原子 comp 不允许调用其他原子操作；确实需要就该作为独立原子节点在 editor 上，而不是 node 中的 node。」
- 「只要是原子的都要改。」（终局形态下，存量原子也要能被手艺修改，不允许存在一批改不动的黑盒内置件）
- 「一切物料入库」「发布不能重启」（物料的终局形态是库里的数据，热更带版本，不绑定发版重启）。
- 反复要求以业界证据排雷：「难道我比业界聪明？我怕有坑。」——所有关键决策必须有主流产品先例或源码级核实支撑，不凭自信独创。

### 1.3 v1 铁律：AI 出草稿，人审核

AI 生产线第一版定位是**副驾驶**，不是自动交付：AI 产出链路草稿与补缺脚本，人审核通过后才允许试运行、发布。业界同档位产品至今同口径：n8n AI Assistant 仍标 Preview；微软 Power Automate Copilot 生成的内容需要人点「保留」。

---

## 2. 终局三层架构

### 2.0 第 0 层：平台门面（永远在 jar，不物料化）

平台底座能力以 Java 长期存在、随版本发布：

- HTTP 引擎（连接池、超时、重试、代理、字符集）；
- 连接与凭证管理（含 `@EncryptField` 加密存储）；
- BPM 薄封装（OPENAPI 网关、签名、13 个端点的协议适配）；
- JSONPath / 数据空间读写等基础工具。

门面能力以**能力注入句柄（handle）**方式暴露：运行时按节点配置的 `connectionId`，把**已完成鉴权**的 http / bpm 句柄注入给该次执行；脚本只能拿到句柄，摸不到密钥原文。连接池、超时、出站白名单、调用审计统一在句柄收口。

对标：Windmill 的 resource 模型——凭证加密存储、按目录授权，脚本只能通过注入的 resource 使用，见 [Windmill 官网](https://www.windmill.dev/)（Resources：encrypted at rest, scoped, accessible only to authorized users）。

### 2.1 第 1 层：原子（终局全部可为库存脚本）

现有 22 个 Cfg 类分两类：

- **19 个业务原子**：httpRequest、condition、setValue、fieldMap、dataPatch、response、12 个 BPM 件、script。终局形态＝手艺可改的脚本文本，入库热更、带版本。
- **3 个流程骨架不迁移**：forLoop、iteratorLoop、switchRoute。它们是 EL 语法与画布容器结构（条件位/分支槽），不是「物料」；其中可配置的表达式文本本来就是热数据。把骨架脚本化等于在脚本里重新发明 LiteFlow 编排，明确不做。

**原子纯净规则（硬约束）**：原子必须是叶子，不允许调用其他原子；一个原子内部需要别的能力，说明那个能力应当作为独立原子节点上浮到画布。多原子复用走 CHAIN 子链路，不做胖节点。n8n 官方文档独立得出同一结论——Code 节点的适用边界明确写着「只是发 HTTP 请求 → 用 HTTP Request 节点」（见 [n8n Code 节点文档](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.code/) 与[自定义节点代码规范](https://docs.n8n.io/connect/create-nodes/build-your-node/reference/code-standards)）。

### 2.2 第 2 层：链路

EL / nodeTree 模型不变。AI 与人生成的都是同一套链路产物，发布、试运行、执行记录、版本治理全部复用现有设施。

### 2.3 两个硬前提（第二步交付，缺一不可对外开放脚本原子）

1. **沙箱白名单**：Groovy 走 `SecureASTCustomizer` 在**编译期**锁死允许的 import 与可调用类（参考 [Groovy 官方安全文档](https://groovy-lang.org/security.html)）；或远期评估换 GraalVM JS 沙箱。默认禁止网络、文件、反射、系统命令、Runtime/ProcessBuilder。现状 Groovy 无沙箱，[databus-script-component.md §7](databus-script-component.md) 已自行标红。
2. **脚本原子宿主**：一个长期存在的 Java 平台组件，统一承担：取参数 → 统一预解析 `{{ }}` → 执行脚本 → 返回值写回 `$.<tag>` → 执行摘要兜底 → 异常翻译。目的：让脚本原子在观测/执行记录/错误口径上与 Java 原子**等价**，不产生「脚本件是二等公民」的断层。

### 2.4 迁移策略

- **新增原子一律脚本入库**，从第二步起不再为一次性业务需求写 Java 组件；
- **存量 19 件不运动式改写**（不做「为了脚本化而脚本化」的批量重写工程）；
- 待第 0 层门面与句柄注入成熟后，以 **httpRequest 为首个迁移试点**（它是门面价值最直接、风险最可控的一件），验证通过再谈其余。

---

## 3. 业界证据与架构原则

### 3.1 代码即 schema，单一事实源，不给内置件手抄第二份 JSON

- **n8n**：节点定义是 TypeScript 类，`INodeType.description.properties` 同时是执行代码与配置表单的唯一来源（[官方代码规范](https://docs.n8n.io/connect/create-nodes/build-your-node/reference/code-standards)）。
- **Activepieces**：`createPiece({...})` + `Property.ShortText({displayName, required...})` 在代码里声明一切，UI 自动渲染（[Piece 定义文档](https://www.activepieces.com/docs/build-pieces/building-pieces/piece-definition.md)）。
- **Apache NiFi**：Processor 的 `PropertyDescriptor` 经 REST 暴露，UI 据此自动生成配置表单。
- **Camunda**：连接器模板 JSON 是构建期产物/随连接器分发的配套描述，不是维护者手填的第二套事实。
- **Windmill**：脚本 `main` 函数参数类型自动推成 JSON Schema，UI 自动生成（[Windmill 概念参考](https://www.windmill.dev/docs/)）。

结论：没有任何主流产品让内置组件维护者在代码之外再手抄一份 schema JSON 入库。databus 选 **Cfg 类 + 注解反射为 schema 唯一事实源**。

### 3.2 「官方连接器也入库」只有 Airbyte 一个范例，且前提不同

Airbyte 的 `actor_definition` / `actor_definition_version` 表确实同时收官方与自定义连接器（custom 标记区分）。但其正当性来自**执行体是外部 Docker 镜像**——注册表登记的是镜像坐标与版本，不是把官方代码搬进库。databus 的 Java 原子执行体就在 jar 里，物化入库只是复制一份会过期的副本，不成立。

### 3.3 AI 只在设计期，运行时零 LLM

AI 生产线只在「设计期」工作（读文档、盘点、写脚本、生成链路）；链路一旦发布，运行时**零 LLM 调用**——性能、成本、可用性三条风险同时归零。Windmill 是同一范式：叶子脚本 + Flow（DAG）+ AI 生成脚本/全流程，但执行就是普通脚本执行。

### 3.4 AI 不瞎猜：查结构化目录 + 多道校验

[n8n-mcp](https://github.com/czlonkowski/n8n-mcp)（czlonkowski，社区高星 MCP server）给 AI 提供 500+ 节点的结构化文档库与管理能力，其工作流是：模板优先 → 按任务/节点检索目录 → 配置 → **节点 minimal 校验 → full 校验 → 整条 workflow 校验**。证明 AI 生产链路可行的关键不是模型多聪明，而是「有机器可读目录可查 + 分层校验兜底」。databus 的 `/options` 合流接口 + 第一步 schema 就是这台机器的目录与校验基础。

旁证：微软 Power Automate Copilot 可基于 OpenAPI 描述文件零代码生成自定义连接器——标准化接口文档是 AI 生产连接器的可行输入（[Power Automate 文档](https://learn.microsoft.com/en-us/power-automate/)）。

### 3.5 交付形态约束：单实例内网交付

项目交付形态是单实例内网（各客户一套一库、停机重启升级），多实例滚动升级才有的「schema 版本串扰」雷基本不发生；且第 0/1 层合流模型（内置反射 + DB 扩展行合流、Java 件不物化）从根上绕开了内置物料版本与库内副本不一致的问题。AI 能力须支持**私有化模型**（Qwen / DeepSeek 等），客户数据不出内网。

---

## 4. 三步路线

### 第一步（当下开工项）：schema-driven 配置表单

**问题现状（已盘点）**：22 个 Cfg 类中 12 个是扁平标量表、8 个含对象数组行编辑（mappings/boList/relate/fields/cases）、2 个动态 KV Map（httpRequest 的 headers/query、dataPatch 的 patch）、3 个嵌套对象（auth/main/rewrite）；`Object` 任意值字段普遍；`connectionId` 在 12 个 BPM 件重复出现。前端 `cmp-defs.ts` 硬编码全部物料；`CmpProps.vue` 只有 forLoop/iteratorLoop/switchRoute 三个手写表单 + script 专用编辑器，其余 13 件靠 JsonCodeEditor 手写 JSON。后端 `/databus/component/options` 现查空表返回空数组，前端从未调用。

**做法**：

1. **注解**：`@DatabusCmp`（类级：名称/图标/分组）+ `@DatabusProp`（字段级：widget / required / options / showWhen / exprRole）。`exprRole = DATA | TARGET` 对齐既有字段角色约定——「要数据」字段走 `{{ }}` 求值，「起名字」字段写裸路径。**不打注解也能渲染**：反射带 Java 类型默认值（String→text、Boolean→boolean、List→行编辑兜底、Object→json）。
2. **widget 集**：text / textarea / number / boolean / select / multiselect / password / keyValueMap / objectRows / json。其中 **json 是逃生舱**（对标 NiFi Custom UI：通用表单覆盖不了的复杂形态退回 JSON 编辑，不追求表单表达 100%）。
3. **`/options` 改合流接口**：内置件反射出 schema + DB 扩展行，每条带 `source = 系统内置 / 自定义`，一个接口就是一个市场。**不做启动同步器、Java 件不物化入库**（依据 §3.1/§3.2）。
4. **前端自研递归 SchemaForm**（约 200–300 行；否决 form-create 这类重型动态表单方案——领域控件需求明确，自研更可控）；领域控件自写：connectionSelect（连接选择）、表达式插入（`{{ $.路径 }}`）、keyValueMap、objectRows。
5. **setValue 试点先行**；JsonCodeEditor 降级为「JSON 高级模式」tab 与表单并存；**cmpData 存储格式不变**，可灰度、零迁移。
6. `cmp-defs.ts` 物料元数据退役后置：小闭环阶段面板常量暂留，待 `/options` 合流稳定后再切。

### 第二步：门面、沙箱与脚本原子宿主

- 第 0 层门面收口 + 能力注入句柄（按 connectionId 注入已鉴权句柄）；
- Groovy 沙箱白名单落地（编译期锁 import/可调用类）；
- 脚本原子宿主组件（§2.3 六件事）；
- 脚本原子编写规范与白名单工具库：Hutool、JSONPath 等**纯工具**可白名单开放；**不暴露 http/bpm 总线动作函数供脚本自由调用**——要碰外部系统就走注入句柄或独立原子，副作用一律上浮画布。
- 此后新原子一律脚本入库；httpRequest 存量迁移试点。

### 第三步：AI 原子物料生产线

端到端链路：读接口文档 → 查机器可读目录（`/options`）→ 盘点已有物料 → 补缺叶子脚本（入库为自定义原子）→ 生成 nodeTree/EL → 渲染画布 → 试运行校验 → **人审** → 发布 → 版本/回滚。模型接入支持私有化（Qwen/DeepSeek）。v1 边界：AI 只出草稿，人审核后才生效；CHAIN 子链路是多原子复用的既有机制，AI 同样遵守原子纯净规则。

---

## 5. 六维尽调结论

1. **技术可行性：高**。每一块都有主流产品先例（代码即 schema、脚本叶子、资源注入、MCP 查目录、分层校验）。
2. **安全：可控，但沙箱必须先行**。沙箱与句柄注入未落地前，脚本原子不得对外开放生产编辑；现状 Groovy 无沙箱是已知红项。
3. **效率：收益本体**。本项目的商业价值主张就是压缩集成交付人天。
4. **性能：近零风险**。运行时零 LLM；脚本原子经宿主执行，与现有 Groovy 脚本节点同量级。
5. **经济：成立**。单次集成的 token 成本几元～几十元，对比人天可忽略；前提是支持私有化模型，不计公网 API 数据合规成本。
6. **现实性：成立但有边界**。标准 REST + 规整文档的系统 v1 可行；**SOAP / 国密签名 / 无文档私有 SDK / 业务语义歧义**四类场景 v1 必须有人介入，不承诺全自动。

---

## 6. 红线与不做清单

- 不做内置 Java 件的启动同步/物化入库；不维护代码之外的第二份 schema 事实源。
- 不做 forLoop/iteratorLoop/switchRoute 的脚本化迁移。
- 不做「node 中的 node」：原子不得调用原子；复用走子链路。
- 不给脚本开放裸 http/bpm 动作函数；外部能力只走注入句柄。
- 运行时不引入 LLM 推理（阶段 6 的 AI 节点是链路上的一个业务组件，与本设计期生产线是两件事，见 roadmap 阶段 6 注记）。
- 不追求表单覆盖 100% 形态，json 逃生舱长期保留。
- 第一步不改变 cmpData 存储格式、不做表结构迁移；databus_component 表只承载扩展/脚本物料行。
