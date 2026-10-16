# AI 原子物料生产线（愿景、终局架构与三步路线）

> 状态：设计档（2026-10-02 确立）。第一步 schema-driven 配置表单已收线（2026-10-10 实测）；脚本原子通道已落地（2026-10-07 自研编译注册通道，§2.5.4）；第三步 agent 化已定稿（2026-10-15，§4 修订）。
> 索引：decisions.md 第四章（AI 原子物料生产线）；本文为该议题正本。

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

### 1.3 v1 铁律：AI 出草稿，人审锁定发布

AI 生产线第一版定位是**副驾驶**，不是自动交付：AI 产出链路草稿与补缺脚本，可自主触发试运行与纠错（2026-10-15 修订 A 细化），人审核通过后才允许发布。业界同档位产品至今同口径：n8n AI Assistant 仍标 Preview；微软 Power Automate Copilot 生成的内容需要人点「保留」。

### 1.4 商业正当性：n8n/Windmill 都有 AI 了，为什么还做（2026-10-02 复盘）

这条生产线不是拿去和 n8n AI Assistant 对卖的功能，是**自己的交付成本武器**——把项目交付从卖人天变成 AI 出草稿、人审核。四点：

1. 他们在客户电脑上连互联网 SaaS；我们在客户机房里连客户自己的 BPM/ERP/MES 老系统（没文档、私有签名、断外网），AI 查他们 500 个 SaaS 目录查不到我们的内网原子。
2. AI 拉平工具层（画布、表单、生成器），拉不平物料纵深；AI 越普及，通用工具越不值钱，纵深越值钱。
3. 运行环境结构性不同：等保内网、国产 OS/库、离线私有化模型；对方不开源或许可证禁止商业转售，进不了场。
4. 我们卖的是「机房里跑通的结果」和交付后的持续兜底，不是工具。

**两个可证伪闸门**：①连续几个真实项目出现客户用 n8n+AI 自搞定、不需要我们；②市场访谈盘出对接量少且三年无强制集成节点（国产化/统建/报送）。任一出现即回头。完整三问复盘（同质化/伪需求/军工得过且过）见 decisions.md 第二章「三问复盘」小节。

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

### 2.3 脚本原子的管控前提（通道已交付，对外开放前须补闸门）

- **安全口径**：脚本原子是完整 Java 类源码（自研 javac 通道，§2.5.4），Java 无 `SecureASTCustomizer` 等价物、SecurityManager 已弃用，**不承诺硬沙箱**；管控口径为「可信作者 + 保存闸门（编译/结构校验失败拒收）+ 权限」，后续按需加 ASM 字节码黑名单/进程隔离。详见 [databus-schema-driven-form.md §5.5](databus-schema-driven-form.md)。
- **保存即生效，无草稿态**：现行保存即编译即注册（「能保存即可运行」）。人工可信作者场景成立，但**对外开放（AI 生产编辑）前必须补草稿态闸门，或在工具接口层收口「AI 只能存草稿、发布必须人点」**——与 §1.3 v1 铁律的张力记录见 §2.5.4 末条。
- **Groovy 无沙箱红项**仅适用于画布自由脚本节点（script / booleanScript），与 Java 脚本原子通道无关。
- **观测等价**：脚本原子直接继承平台基类，在观测/执行记录/错误口径上与 Java 原子**等价**，不产生「脚本件是二等公民」的断层。

### 2.4 迁移策略

- **新增原子一律脚本入库**（通道已具备，2026-10-07 起生效），不再为一次性业务需求写 Java 组件；
- **存量 19 件不运动式改写**（不做「为了脚本化而脚本化」的批量重写工程）；
- 待第 0 层门面与句柄注入成熟后，以 **httpRequest 为首个迁移试点**（它是门面价值最直接、风险最可控的一件），验证通过再谈其余。

### 2.5 组件结构模型（2026-10-02 拍板：Java 内置件与脚本原子同构）

> 拍板：脚本原子语言定为 Java，脚本自己就是 `NodeComponent` 子类，早期设想的「外层包裹式宿主组件」取消，宿主职责走基类继承。
> 原设想的 javax-pro 执行器路线（ScriptAtomComponent 基类、final process 模板、lf_script 物料发布、异常剥壳绕行、单例/构造器纪律等）经 2026-10-02 实跑考据，落地时弃用——考据存档于本文 git 历史；现行机制见 **§2.5.4 实施记录**。

#### 2.5.1 画布上的三种节点存在形态

| 维度 | ① Java 内置件（存量 19+3） | ② 注册式脚本原子（已落地） | ③ 画布自由脚本节点（script / booleanScript） |
| --- | --- | --- | --- |
| 执行体 | Spring bean `@LiteflowComponent` | databus_component.scriptBody 编译类（自研 javac 通道） | lf_script 库存 Groovy 文本 |
| nodeId | 注册名（setValue…） | **物料 code**（物料级一份） | 画布 tag（实例级一份） |
| 实例差异 | `.data(cmpData)` + `.tag()` | `.data(cmpData)` + `.tag()` | 脚本文本本身即实例逻辑 |
| 配置 | XxxCfg + `@DatabusProp` | 内嵌固定名 `Cfg` + 同一套注解 | ScriptCfg{language,script}，专用编辑器 |
| schema 来源 | 启动反射 bean | **保存物料时编译反射一次，物化 param_schema** | 无配置 schema（editor=script） |
| 基类 | `DatabusNodeComponent` | 同①（无脚本专用基类） | 过程式文本，绑定 `databusContext` |
| 多实例共享 | bean 天然共享 | 同 code 全链路共享一份（FlowBus 注册） | 不共享，一节点一脚本 |
| 热更 | 重启 | 保存即同 code 覆盖注册 | 随链路重发布 |

形态 ② 与 ① 对前端/EL/运行时**完全同构**：拖一个脚本原子上画布，与拖 setValue 没有任何结构差异，
唯一差别是执行体来源（jar bean vs 库存编译类）。形态 ③ 维持现状，不承载注册物料。

#### 2.5.2 脚本原子标准长相（AI 产出与手工编写的统一模板）

平台基类 `DatabusNodeComponent` 提供全部公共能力：数据空间 get/save、表达式 `resolveParam`、
执行摘要 `resultSummary`、记录规整 `toRecordList`。脚本原子直接继承它，要写的只有业务方法
`process()` 与内嵌 Cfg：

```java
@DatabusCmp(code = "mySetValue", name = "我的赋值", icon = "ph:pencil-simple",
            color = "#67c23a", group = "business", description = "把值写入指定路径")
public class MySetValueAtom extends DatabusNodeComponent {

    public static class Cfg {
        @DatabusProp(label = "写入路径", required = true, exprRole = ExprRole.TARGET)
        private String path;
        @DatabusProp(label = "写入值", widget = WidgetKind.JSON, exprRole = ExprRole.DATA)
        private Object value;
    }

    @Override
    public void process() {
        Cfg cfg = getCmpData(Cfg.class);
        save(cfg.getPath(), resolveParam(cfg.getValue()));
        resultSummary("赋值：" + cfg.getPath());
    }
}
```

两条仍有效的纪律：

- **无状态纪律**：脚本实例经 FlowBus.addManagedNode 同 code 全链路共享一份，**实例字段禁止
  承载执行状态**（多链路并发互相踩）；Cfg 是每次 getCmpData 反序列化的新对象不受影响；需要跨
  步骤状态一律走上下文数据空间；
- LiteFlow 生命周期钩子（isAccess / beforeProcess / onSuccess / onError / rollback）**v1 约定
  不开放覆写**，条件执行走 EL IF + 布尔节点上浮画布。

#### 2.5.3 句柄与门面落位（后补项，agent v1 之后）

现状 12 个 BPM 件的固定姿势：`SpringUtils.getBean(XxxConnector.class)` +
`getDatabusContext().getConnection(connectionId)`。脚本原子不允许直接 `SpringUtils`/`ContextAwareHolder`
（无静态白名单可审计），统一收口到基类（`DatabusNodeComponent` 增量开受保护口 `protected HandleFactory handles()`）：

```java
// 脚本内唯一合法姿势：
BpmHandle bpm = handles().bpm(cfg.getConnectionId());
bpm.createSession(req);
```

`HandleFactory` 随第 0 层门面一起交付（§4 第二步后补项）。副作用一律上浮为叶子原子的铁律不变（§2.1）。

#### 2.5.4 实施记录（2026-10-07）：自研编译注册通道

一句话说清这套东西：**源码存数据库，点保存时用 JDK 自带的编译器在内存里编成类，当场注册进
LiteFlow 当普通组件用，不用重启**。没引入官方的 liteflow-script-javax-pro / Liquor（pom 里
查无此依赖），核心就两个类：管编译的 JavaSourceCompiler、管注册的 ScriptComponentRegistrar，
都在 `org.dromara.databus.script.host` 包下。

**点一次保存，发生了什么**（saveScript 方法，全程包在一个数据库事务里）。第一步不是落库，
是先编译。用的就是 JDK 自带的 javac（JavaSourceCompiler 里 `ToolProvider.getSystemJavaCompiler()`
拿到的那个），全程纯内存：源码不写临时文件，编出来的字节码也不落盘。编不过，报错带行列号直接
打回，库里一个字不写——「能保存的就能跑」不是口号，是事务保证的。

编过了做体检：源码里必须有、且只能有一个打 @DatabusCmp 注解的主类；内嵌的 Cfg 类要能找到
（注解显式指定优先，其次找「主类名+Cfg」，兜底找唯一以 Cfg 结尾的）；主类的父类要和声明的
节点类型对得上（普通/布尔/循环/迭代/分支五类）——这种运行时才炸的转型错编译器查不出来，只能
自己把关。体检顺手把 Cfg 上的注解反射成参数表单配置（param_schema）存进主表，前端 SchemaForm
直接渲染。再查组件编码：注解里写死的 code 要么留空、要么必须和表编码一致，防止一个组件两套名。

都过了才写库：databus_component_version 版本表追加一条历史，databus_component 主表更新；
事务提交后才做注册，注册那步实例化或注入失败，整个事务回滚，现网继续跑旧版，等于啥都没发生。
回滚功能也一样简单：拿旧版本重走一遍保存流程，版本号照常追加。

**怎么注册**。内存字节码由自写的类加载器 ScriptClassLoader 变成类，再用 Spring 的机制造实例——
@Autowired 注入、@PostConstruct 都生效，这正是迁移过来的 18 个组件要的待遇；但故意不登记进
Spring 容器：一来动态类名会搞乱单例池，二来老类引用攥在容器手里就永远卸载不掉。实例直接调
FlowBus.addManagedNode 塞进 LiteFlow，同编码覆盖旧版、当场生效——官方脚本路线靠规则库轮询、
几秒后才生效，这边没有等待。这里刻意绕过了 LiteFlow 自带的注册器（LiteflowNodeBuilder）：它的登记习惯是只记一个
「类名字符串」，等真正执行这个节点时才拿这串名字去找类，用的是应用程序自己的类加载器——
就是当初负责加载 jar 包里所有类的那个「图书管理员」。可我们保存时编出来的类放在自写的
ScriptClassLoader 里，纯内存，应用的类加载器压根不知道有这号人，按名字找必然扑空。所以不走
「报名字」的路子，走 addManagedNode 直接把编好、造好的实例塞到 LiteFlow 手里——不递名片
让它回头查，直接把人领到工位坐下。

**部署形态的一个坑**。javac 编译源码不光看源码本身，还得找到 import 进来的所有东西——基类、
LiteFlow 的类、Spring 注解，不然类型检查没法做；它找东西靠一份「书架清单」，清单里得是磁盘上
真实存在的文件夹或 jar 文件。开发环境跑，依赖本来就是磁盘上散着的一堆 jar，指给它看就完了。
但用 Spring Boot 打成 fat jar 部署后，所有依赖 jar 都被塞进大 jar 内部——jar 文件里又套着
jar 文件，而这种内嵌 jar 在磁盘上根本不是独立文件，只是大 jar 压缩包里的一个条目，javac
打不开这种套娃。所以首次编译会把内嵌 jar 解压到临时缓存目录，恢复成它认识的普通 jar，之后
一直复用这份缓存。这坑不是独有的——只要直接调 JDK 编译器（javax.tools 这条路）又住在
fat jar 里都得解，Liquor v1.5.3 就是专门为这事发的版。

**重启之后**。保存时编出来的类只活在内存里，重启就全没了；好在源码在数据库里，什么都不丢。
所以启动时（StartupRegistrar）把库里所有脚本源码捞出来，把「保存时干的事」原样再干一遍——
重新编译、重新注册，物料就全回来了。哪个脚本这时候编不过，标成失败状态，启动照常，一个坏
脚本不拖死整个应用；健康快照（registrar.snapshot）随时能查现在注册了哪些、哪个是坏的。替换
物料时，旧类的类加载器引用放掉，垃圾回收把旧类收走，内存不积。

**跟官方方案的关系**（2026-10-16 考据补注）：官方 javax-pro 底层的 Liquor 干的同样是
「调 javac」的活（40KB、零依赖），编译器同一个，不存在谁另造了编译器。不用它的执行路线是因为
装不下我们的形态：它不给组件走 Spring 生命周期（@Autowired 会是 null），lf_script 表也存不下
「组件＋参数表单＋版本审计」这一整套。原考据里担心的官方特有机制问题（单例注入模式、异常剥壳
那套）也随之消失——咱们的实例是注册期造出来的普通对象，生命周期跟 Spring bean 一致。真正
自己写的就「存码—编译—注册」这层调度，官方本无对应物；组件跑起来跟 jar 内置件同一条执行
路径，还少一层包装。

**还没做的一件事**：原设计「草稿 → 人审 → 发布」两道门没落地，现状保存即生效。人当作者
没问题，但跟 §1.3 铁律（AI 出草稿、人审后才生效）冲突——**agent v1 之前必须补**：要么草稿
状态的行不注册，要么工具接口层限死「AI 只能存草稿，发布必须人点」。

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

### 第一步：schema-driven 配置表单（已收线，2026-10-10 实测）

详细设计见 [databus-schema-driven-form.md](databus-schema-driven-form.md)，收线记录见 work-log（2026-10-07 / 2026-10-10）。一句话收线：22 个 Cfg 类注解反射出 schema，`/options` 内置反射与 DB 扩展行合流（每条带 source 标记），前端自研递归 SchemaForm + 领域控件（json 逃生舱长期保留），cmpData 存储格式不变零迁移；开工前盘点的 13 件手写 JSON / cmp-defs 硬编码 / 空表问题全部消除，cmp-defs 退役提前完成（2026-10-07，面板仅剩 STRUCTURE_DEFS 结构件）。

### 第二步：门面与能力注入句柄（后补项，agent v1 试水之后）

> 2026-10-15 修订 D 改道：原「第二步全做完 → 第三步」改为「**脚本原子通道 → AI agent v1 试水 →
> 能力把手后补**」。脚本原子通道（保存管线 + 物料 CRUD）已按自研路线落地（2026-10-07，见 §2.5.4），
> 不含注入句柄。

- 第 0 层门面收口 + 能力注入句柄（按 connectionId 注入已鉴权句柄），落位见 §2.5.3；
- httpRequest 存量迁移试点（原 2.4 计划不变）；
- 安全管控：字节码黑名单扫描按需后补（管控口径见 §2.3）；
- 脚本原子编写规范与白名单工具库：Hutool、JSONPath 等**纯工具**可白名单开放；**不暴露
  http/bpm 总线动作函数供脚本自由调用**——要碰外部系统就走注入句柄或独立原子，副作用一律上浮画布。

### 第三步：AI 原子物料生产线

端到端链路：读接口文档 → 查机器可读目录（`/options`）→ 盘点已有物料 → 补缺叶子脚本（入库为自定义原子）→ 生成 nodeTree/EL → 渲染画布 → 试运行校验 → **人审** → 发布 → 版本/回滚。模型接入支持私有化（Qwen/DeepSeek）。v1 边界：AI 只出草稿，人审核后才生效；CHAIN 子链路是多原子复用的既有机制，AI 同样遵守原子纯净规则。

### 修订（2026-10-15）：agent 化交互定稿与节奏改道

**A. 第三步形态＝带工具的 agent，非「生成器交作业」**
- AI 以工具循环自主工作：查目录 → 补缺脚本存草稿 → 拼/改链路草稿 → 触发试运行 → 读执行记录 → 纠错重试 → 出报告；人只围观、打断、审核。
- 同源铁律：AI 调用的工具与人工界面操作**共用同一套底层 API**——AI 能加节点，人能 undo；AI 存的草稿人可改；AI 动作全落现有事务/版本体系。
- 自纠错环：自动纠错 **3 轮**不收敛即停，输出「试过什么/卡在哪」报告求助（执行记录已有节点级输入/输出/异常/上下文快照，AI 诊断口粮零新基建）。
- 红线：AI **只能改自己本会话产出的草稿**（新生成组件与链路草稿）；存量物料与他人已发布链路只读，需改动打报告由人执行。
- 形态辨析（2026-10-16 补注）：两种形态的分界在**反馈环**——「生成器交作业」一次性吐链路产物，看不见其运行结果，出错只能靠人转述再重生成；「带工具的 agent」有眼（读执行记录）有手（改草稿），才能自纠错。上面 3 轮上限 / 同源铁律 / 红线皆为此形态的配套纪律。

**B. 大脑/手脚分体**
- 大脑＝SnailAI（fork 已部署，独立 8900 端口）：模型接入（私有化 Qwen/DeepSeek）、agent 循环引擎、会话记忆、RAG 文档解析，均现成。
- 手脚＝databus 后端新增工具接口：查组件目录（两级查询）、脚本组件存草稿、链路草稿生成/修改、触发试运行、读执行记录；挂接走 ruoyi-common-mcp 或 SnailAI tool 接口。
- 分工原则：通用的（模型/循环/记忆/RAG）不做；业务侧四件必做且是成败所系——工具设计、系统提示词、目录知识质量、评估校准。
- 为何不自研 + SnailAI 现状（2026-10-16 补注）：与 javac 通道同一取舍逻辑——通用四件是现成大轮子（底层 Spring AI，上层 SnailAI 企业级封装），自研＝重复造轮；业务四件无现成轮子，才是必做部分。SnailAI＝aizuda（SnailJob 同门）企业级 Agent 平台：Spring Boot 4 + Spring AI 同栈，多模型 / Agent 编排 / RAG（Milvus/ES）/ 长期记忆 / Skill / OpenAPI / 管理后台，Apache 2.0；项目尚早期（gitee star ~78），但 fork 内全套现成（ruoyi-snailai-server 独立服务 + ruoyi-common-ai 客户端 + 前端会话栏），RuoYi-Cloud-Plus 官方亦已集成。可用状态分两档：**聊天档已通**（登录用户自动注册 OpenAPI 用户，chat.ui.embed 嵌入）；**干活档未通**——databus 五工具与随身知识包均未落地，即 v1 待办排头两项。挂点机制＝SnailJob 式 Server/客户端分离：`@EnableSnailAiAgent` 挂在业务进程，agent 循环调工具时在本地执行。

**C. 画布围观＝后端事件广播（v1 一步到位，文字直播兜底）**
- 工具执行后发事件 → 前端 SSE/WebSocket 订阅 → 调与人工操作同一套画布结构化操作函数（前插/后插/边上插），节点连线实时出现；会话文字直播保留为兜底（事件丢失/刷新页面时仍有全程记录）。v1 不含节点动画花活。
- 打断：agent 每次调工具前，工具层检查打断标志，不依赖 SnailAI 支持中断。
- 冲突规则：AI 编辑期间该草稿锁定人工操作（只能围观与喊停）。

**D. 三步走节奏改道**
- 原计划「第二步全做完→第三步」改为：**脚本原子通道**（宿主＋保存管线＋物料 CRUD，不含注入句柄）→ **AI agent v1 试水** → 能力把手＋httpRequest 迁移试点后补。
- 依据：AI 拼链路复用现有 19 个内置原子；v1 需 AI 现写的缺料基本为纯计算脚本原子，不依赖注入句柄；前端 AI 会话栏（连 SnailAI）已入库。

**E. 领域知识分层注入（通用模型 → databus 专家）**
- 随身层（系统提示词，几 KB，每轮携带）：身份、红线、工作 SOP、术语表（链路/组件/原子/tag/上下文/CHAIN）。
- 查询层（工具现查）：组件目录/草稿现状/执行记录——schema-driven 物料即 AI 口粮；**事实走查询不走背诵**，目录更新 AI 即时可见。
- 检索层（RAG 知识库）：接口文档、对接案例。
- 套路层（SnailAI skill）：v1 不做，从实战纠错经验沉淀。
- 工具返回两级查询（目录先摘要列表、选中再查全量 schema），防上下文撑爆导致「失忆」。

**v1 待办（顺序）**：随身知识包（系统提示词）骨架 → 工具清单（名称/参数/返回）→ 存库边界细则（草稿自动、发布必须人点的落地口径）。

---

## 5. 六维尽调结论

1. **技术可行性：高**。每一块都有主流产品先例（代码即 schema、脚本叶子、资源注入、MCP 查目录、分层校验）。
2. **安全：可控，但管控闸门必须先行**。脚本原子为 Java（自研 javac 通道，无字节码级沙箱）：保存即编译即注册、无独立人审闸门（§2.5.4）——人工可信作者场景成立，对外开放生产编辑前须补权限与草稿态闸门；句柄注入未落地前不开放总线动作能力；Groovy 无沙箱红项仅限画布自由脚本节点。
3. **效率：收益本体**。本项目的商业价值主张就是压缩集成交付人天。
4. **性能：近零风险**。运行时零 LLM；脚本原子即普通节点组件执行，与现有 Groovy 脚本节点同量级。
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
- cmpData 存储格式不变（第一步落地已验证，长期保留）；databus_component 表只承载扩展/脚本物料行。
