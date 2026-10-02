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

### 1.4 商业正当性：n8n/Windmill 都有 AI 了，为什么还做（2026-10-02 复盘）

这条生产线不是拿去和 n8n AI Assistant 对卖的功能，是**自己的交付成本武器**——把项目交付从卖人天变成 AI 出草稿、人审核。四点：

1. 他们在客户电脑上连互联网 SaaS；我们在客户机房里连客户自己的 BPM/ERP/MES 老系统（没文档、私有签名、断外网），AI 查他们 500 个 SaaS 目录查不到我们的内网原子。
2. AI 拉平工具层（画布、表单、生成器），拉不平物料纵深；AI 越普及，通用工具越不值钱，纵深越值钱。
3. 运行环境结构性不同：等保内网、国产 OS/库、离线私有化模型；对方不开源或许可证禁止商业转售，进不了场。
4. 我们卖的是「机房里跑通的结果」和交付后的持续兜底，不是工具。

**两个可证伪闸门**：①连续几个真实项目出现客户用 n8n+AI 自搞定、不需要我们；②市场访谈盘出对接量少且三年无强制集成节点（国产化/统建/报送）。任一出现即回头。完整三问复盘（同质化/伪需求/军工得过且过）见 roadmap《产品定位与差异化》「三问复盘」小节。

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

1. **脚本原子的安全管控**：
   - **【2026-10-02 修订】注册式脚本原子语言已拍板为 Java（`liteflow-script-javax-pro`，Liquor 内存 javac），不再走 Groovy**——原子脚本是完整 Java 类源码，外层类与内嵌 Cfg 直接打第一步同一套 `@DatabusCmp/@DatabusProp`，schema 编译期反射产出、零执行，与 Java 组件一套契约；Groovy 无字段级注解且反射 static 触发 clinit 的问题由此绕开，可行性已实跑验证。安全管控随之改为「可信作者 + 保存后人审闸门 + 发布控制」，后续按需加 ASM 字节码黑名单/进程隔离；Java 无 SecureASTCustomizer 等价物，SecurityManager 已弃用，不承诺硬沙箱。详见 [databus-schema-driven-form.md §5.5](databus-schema-driven-form.md)。
   - 原方案（存档）：Groovy 走 `SecureASTCustomizer` 在**编译期**锁死允许的 import 与可调用类（参考 [Groovy 官方安全文档](https://groovy-lang.org/security.html)）；或远期评估换 GraalVM JS 沙箱。默认禁止网络、文件、反射、系统命令、Runtime/ProcessBuilder。现状 Groovy 无沙箱，[databus-script-component.md §7](databus-script-component.md) 已自行标红——该红项现仅适用于画布自由脚本节点（script / booleanScript）。
2. **脚本原子宿主**：一个长期存在的 Java 平台组件，统一承担：取参数 → 统一预解析 `{{ }}` → 执行脚本 → 返回值写回 `$.<tag>` → 执行摘要兜底 → 异常翻译。目的：让脚本原子在观测/执行记录/错误口径上与 Java 原子**等价**，不产生「脚本件是二等公民」的断层。

### 2.4 迁移策略

- **新增原子一律脚本入库**，从第二步起不再为一次性业务需求写 Java 组件；
- **存量 19 件不运动式改写**（不做「为了脚本化而脚本化」的批量重写工程）；
- 待第 0 层门面与句柄注入成熟后，以 **httpRequest 为首个迁移试点**（它是门面价值最直接、风险最可控的一件），验证通过再谈其余。

### 2.5 组件结构模型（2026-10-02 拍板：Java 内置件与 javax-pro 脚本原子同构）

> 触发：脚本原子语言定为 Java（javax-pro，见 [databus-schema-driven-form.md §5.5](databus-schema-driven-form.md)）。
> 脚本自己就是 `NodeComponent` 子类，§2.3 第 2 条设想的「外层包裹式宿主组件」**取消**，宿主职责改为**基类继承**。
> 以下结构均经本地实跑验证（编译/反射/继承执行，证据见 schema 设计 §5.5.3 与本节 §2.5.7）。

#### 2.5.1 画布上的三种节点存在形态

| 维度 | ① Java 内置件（存量 19+3） | ② 注册式脚本原子（第二步新增） | ③ 画布自由脚本节点（script / booleanScript） |
| --- | --- | --- | --- |
| 执行体 | Spring bean `@LiteflowComponent` | lf_script 库存 Java 类源码（javax-pro 编译） | lf_script 库存 Groovy 文本 |
| nodeId | 注册名（setValue…） | **物料 code**（物料级一份） | 画布 tag（实例级一份） |
| 实例差异 | `.data(cmpData)` + `.tag()` | `.data(cmpData)` + `.tag()` | 脚本文本本身即实例逻辑 |
| 配置 | XxxCfg + `@DatabusProp` | 内嵌固定名 `Cfg` + 同一套注解 | ScriptCfg{language,script}，专用编辑器 |
| schema 来源 | 启动反射 bean | **保存物料时编译反射一次，物化 param_schema** | 无配置 schema（editor=script） |
| 基类 | `DatabusNodeComponent` | `ScriptAtomComponent`（其叶子子类） | 过程式文本，绑定 `databusContext` |
| 多实例共享 | bean 天然共享 | 同 code 全链路共享一份已编译脚本 | 不共享，一节点一脚本 |
| 热更 | 重启 | 物料改文本→重编译→reloadScript | 随链路重发布 |

形态 ② 与 ① 对前端/EL/运行时**完全同构**：拖一个脚本原子上画布，与拖 setValue 没有任何结构差异，
唯一差别是执行体来源（jar bean vs 库存编译类）。形态 ③ 维持现状，不承载注册物料。

#### 2.5.2 类层次

```
com.yomahub.liteflow.core.NodeComponent            （LiteFlow）
└── DatabusNodeComponent                            （现有，平台共同基类）
      · 数据空间：get/getOptional/getOrDefault/save
      · 表达式：resolveParam
      · 执行摘要：resultSummary
      · 记录规整：toRecordList
      · 【新】受控 Spring 能力：protected <T> T bean(Class)（封装 ContextAwareHolder，禁脚本直接摸 SpringUtils）
      │
      ├── data/bpm/protocol/... 存量 19 个 @LiteflowComponent（不动）
      ├── flow 下 3 个骨架件（永不脚本化）
      │
      └── ScriptAtomComponent（第二步新增，abstract）
            · 脚本原子的唯一父类；库存 Java 脚本 extends 它
            · 内嵌 Cfg 规范（固定类名 Cfg，getCmpData(Cfg.class)）
            · 句柄入口：protected HandleFactory handles()（门面收口，§2.5.5）
            · 【2026-10-02 拍板 A：模板式】final process() 收口异常翻译/摘要兜底，脚本只实现 doProcess()
```

模板式纪律（拍板 A，2026-10-02 经真·LiteFlow 链路实跑修订）：

- `process()` 在基类为 `final`：统一 try/catch + 摘要缺省兜底「完成」；脚本**只实现
  `protected abstract void doProcess()`**，这是 AI 生成模板里唯一允许出现的业务方法；
- **异常翻译必须用「无 cause 翻译异常」**（实跑踩坑，硬约束）：javax-pro 的
  `ScriptExecutor.execute`（2.16.3.1 源码 53-67 行）对从 executeScript 抛出的异常统一剥壳——
  只要 `e.getCause() instanceof RuntimeException` 就抛 cause，带 cause 的包装异常永远到不了
  response/CmpStep。正确写法：`throw new RuntimeException("ATOM_EXEC_FAIL tag=.. : 原始类名:msg")`
  **不传 cause**（getCause()=null 走原样 rethrow 分支），原始栈用
  `translated.setStackTrace(e.getStackTrace())` 保留定位。实跑证实 response.message /
  response.cause / CmpStep.exception 三处均拿到翻译文案；项目 DatabusExecutor 现有取数路径零改；
- **单例并发纪律**：脚本编译产物是跨执行共享的单例（JavaxProExecutor.compiledScriptMap），
  执行器每次调用前注入 refNode/nodeId/type/self、finally removeRefNode。故脚本类**禁止实例字段
  承载执行状态**（多链路并发互相踩）；Cfg 是每次 getCmpData 反序列化的新对象不受影响；需要跨步骤
  状态一律走上下文数据空间；
- **构造器纪律**：脚本必须使用默认无参构造器（convertScript 固定生成 `new X()`，无参构造器是
  硬要求）；禁止显式构造器、实例初始化块、static 块、非常量字段初始化表达式——原因见 §2.5.4
  （这些代码在发布加载时即执行，先于任何人审后的"运行"动作）；
- LiteFlow 生命周期钩子（isAccess / beforeProcess / onSuccess / onError / rollback）**v1 约定
  不开放覆写**（注意：是约定不是技术限制——JavaxProExecutor 对全部钩子都有 executeXxx 委托，
  技术上可覆写；v1 主动收窄，条件执行走 EL IF + 布尔节点上浮画布，未来由基类开受保护模板口子）。

脚本原子标准长相（AI 产出与手工编写的唯一模板）：

```java
@DatabusCmp(code = "mySetValue", name = "我的赋值", icon = "ph:pencil-simple",
            color = "#67c23a", group = "business", description = "把值写入指定路径")
public class MySetValueAtom extends ScriptAtomComponent {

    public static class Cfg {
        @DatabusProp(label = "写入路径", required = true, exprRole = ExprRole.TARGET)
        private String path;
        @DatabusProp(label = "写入值", widget = WidgetKind.JSON, exprRole = ExprRole.DATA)
        private Object value;
    }

    @Override
    protected void doProcess() {
        Cfg cfg = getCmpData(Cfg.class);
        save(cfg.getPath(), resolveParam(cfg.getValue()));
        resultSummary("赋值：" + cfg.getPath());
    }
}
```

#### 2.5.3 包结构（目标态，增量不搬迁）

```
org.dromara.databus
├── component
│   ├── DatabusNodeComponent.java          # 平台基类（存量，仅增量 bean() 等受控方法）
│   ├── schema/                            # 第一步交付
│   │   ├── annotation/  model/  registry/
│   │   ├── introspect/CfgIntrospector.java   # ★纯函数 introspect(Class<?>):CmpSchema，①②共用
│   │   └── scanner/ComponentSchemaScanner.java # 只扫形态①（Spring beans）
│   ├── script
│   │   ├── ScriptAtomComponent.java       # ②脚本原子基类（第二步）
│   │   ├── ScriptAtomCompiler.java        # ②保存管线编译/反射（DynamicCompiler，只编译不执行）
│   │   └── ScriptCfg.java                 # ③画布自由脚本节点（现状不动）
│   ├── bpm/ data/ flow/ logical/ protocol/   # ①存量 19 件，包结构不动
├── service/impl
│   ├── ScriptAtomService.java             # ②物料 CRUD 编排（编译→schema→databus_component/lf_script）
│   └── RulePublishService.java            # 链路发布（现状）；物料发布脚本部分被 ScriptAtomService 复用
└── connector/                             # 第 0 层门面：Connection/Connector/Handle（第二步收口）
```

纪律：存量包一个文件不搬；新东西只在 `schema/` 与 `script/` 增量。

#### 2.5.4 形态 ② 的生命周期（物料级，与链路发布解耦）

```
保存草稿：脚本文本 → ScriptAtomCompiler（自研通道，Liquor DynamicCompiler 只编译不实例化）
         → loadClass 反射 @DatabusCmp + 内嵌 Cfg 的 RUNTIME 注解 → CfgIntrospector 产出 schema
         编译失败：getErrors() 行号诊断回前端，拒收             （实跑已验证）
         通过：databus_component 行（source=CUSTOM, param_schema, 状态=草稿）
               脚本文本暂存（草稿表/同表草稿字段，第二步细化）
         ★此阶段不 new 实例：不触达构造器/字段初始化/static 块，恶意代码无执行窗口
人审 → 发布物料：lf_script 写 nodeId=code / script_language=java / script_type=script
         + databus_component 状态=启用；/options 即刻可见
         框架加载（Rule-DB 候选编译/executor.load）时 new 一次单例——构造器在此首次执行，
         已在人审闸门之后；模板纪律（§2.5.2 构造器纪律）把此窗口的攻击面压到常量级
链路侧：EL 中 code.tag(...).data(...) 引用；Rule-DB 按 nodeId 取已编译单例执行
改物料：重跑保存管线 → content_md5 冲突校验（同标识不同文本报错，现有纪律）
         → executor.load(nodeId, 新源码) 热替换（实跑已验：同名类重新编译，新 ClassLoader/
           新 identity，旧单例回收，无需类名带版本）
删物料：先查链路引用（EL 含该 code 的链路），有引用拒删；无引用删 lf_script + component 行
```

> 安全窗口更正（2026-10-02 源码核实）：框架自带的 `JavaxProExecutor.validate()` 并非纯编译——
> 它调 `Scripts.eval(codeSpec)`，会执行 convertScript 追加的 `new X()`，即**构造器、实例字段
> 初始化表达式、类初始化 static 块在 validate 时就跑一次**；`load()` 非启动分支同理。故保存草稿
> 校验**不走框架 validate**，走自研 ScriptAtomCompiler 只编译反射；实例化只发生在人审后的发布
> 加载。这与 §5.5「可信作者 + 人审闸门」模型一致，但文档里不得再写"保存即编译校验、零执行"
> 这种过头话——准确口径是「保存期不实例化；发布加载期执行一次构造器」。

时序事实（源码核实，与项目 Rule-DB 模式匹配）：

- 启动阶段 `startUpPhase=true` 时，JavaxProExecutor.load 只缓冲 CodeSpec，由 FlowInitHook
  二阶段批量编译；项目 `rule-db active`，FlowExecutor.init 走 Rule-DB 分支（123-137 行），
  官方已专门处理：init 末尾 executeHook + `startUpPhase` 复位 false（注释明确为修复运行期
  懒加载"not loaded"问题）；
- 故项目运行期两条脚本注册路径（lf_script 懒加载、试运行 registerScriptNodes）都稳定走
  非启动分支（直接 eval 入 compiledScriptMap），实测同款时序下执行/热更全部通过；
- lf_script 表零改动：2.16.3.1 DDL 自带 `script_type` + `script_language`（VARCHAR(32),
  可空）列，Rule-DB 的 ScriptCandidateLoader 以 `new Node(id,name,type,script,language)`
  原样透传 language 到 ScriptExecutorFactory 按 SPI 选执行器；发布管线
  `PublishScriptRequest.language("java")` 即可（RulePublishService 现有透传不改）。

关键区别：形态 ③ 的脚本随**链路发布**推 lf_script（nodeId=tag，现状）；
形态 ② 的脚本随**物料发布**推 lf_script（nodeId=code），先于任何链路存在。RulePublishService 现有
按 `ScriptNodeSpec(nodeId,type,language,script)` 透传的管线对形态 ② 可直接复用，只是数据来源从画布节点换成物料行。

#### 2.5.5 句柄与门面落位

现状 12 个 BPM 件的固定姿势：`SpringUtils.getBean(XxxConnector.class)` +
`getDatabusContext().getConnection(connectionId)`。脚本原子不允许直接 `SpringUtils`/`ContextAwareHolder`
（无静态白名单可审计），统一收口到基类：

```java
protected abstract class ScriptAtomComponent extends DatabusNodeComponent {
    protected HandleFactory handles();   // 第二步门面收口后实现
}
// 脚本内唯一合法姿势：
BpmHandle bpm = handles().bpm(cfg.getConnectionId());
bpm.createSession(req);
```

门面未收口前（第二步前半截）可先只提供 `bean(Class)` 受控透传并在基类 Javadoc 标注过渡用法；
`HandleFactory` 随第 0 层门面一起交付。副作用一律上浮为叶子原子的铁律不变（正本 §2.1）。

#### 2.5.6 与第一步 schema-driven 的衔接（对第一步设计的两处细化）

1. `CfgIntrospector.introspect(Class<?> cfgClass)` 必须是**无 Spring 依赖的纯函数**：
   形态 ① scanner 传入 `XxxCfg.class`；形态 ② ScriptAtomCompiler 传入编译出的 `Outer$Cfg`。
   第一步建包时即按此边界落地，第二步零改。
2. `@DatabusCmp.cfg()`：形态 ① 必填（显式绑定，理由见 schema 设计 §3.1）；
   形态 ② 缺省——固定反射外层类的内嵌 `public static class Cfg`，找不到则编译期/保存期报错。
   同一条注解，两种取 cfg 类的路径，在 scanner 与 compiler 各自解析一次。

#### 2.5.7 实跑证据与待实测项

**已验证 A 批——单机构造验证**（JDK21 + javax-pro 2.16.3.1 + Liquor 1.6.6 + liteflow-core 2.16.3.1）：
脚本类继承平台基类、调基类 protected 能力（save/resolve/摘要）、内嵌 Cfg 注解零执行反射、
编译错误行号诊断、编译 ClassLoader 显式传平台加载器（null 时 Liquor 兜底 TCCL，字节码已核实）。

**已验证 B 批——2026-10-02 真·LiteFlow 链路实跑**（FlowExecutor + EL 建链 + execute2Resp 全链路，
探针 `temp/databus-groovy-spike/flow/`，临时目录勿提交）：

1. `final process()` 与 javax-pro 包装零冲突：执行器经 `withExecutableCmp` 注入现场后普通虚调用
   `cmp.process()`，final 模板方法正常执行，doProcess 业务输出正确；
2. 单例 + 现场注入：构造器仅 load 时执行一次；同一脚本实例被同链两个节点复用，两轮
   `getCmpData(Cfg.class)` 分别拿到各自 data（p-a/1、p-b/2），getTag/getNodeId 各自正确
   （t1/t2）——refNode 每次注入、finally removeRefNode 机制证实；内嵌 public static class Cfg
   的 Jackson 反序列化与 Java 件同一通道（NodeComponent.getCmpData，ObjectMapper 走项目
   已带的 jsr310 等模块）；
3. 异常剥壳与绕行：带 cause 翻译异常被 ScriptExecutor.execute 剥成最内层（旧设计假设证伪）；
   无 cause 翻译异常原样到达 response.message/response.cause/CmpStep.exception 三处；
4. 热更：运行期 `ScriptExecutorFactory...getScriptExecutor("java").load(nodeId, newSrc)`
   同名类重新编译生效（新 identity、新逻辑输出），不依赖类名带版本；isCache 默认 false；
5. Rule-DB 匹配（源码级核实）：lf_script DDL 含 script_type/script_language；
   ScriptCandidateLoader 原样透传 language；Rule-DB init 分支专门复位 startUpPhase，
   运行期懒加载/热更走非启动分支；
6. 执行步骤采集：execute2Resp 返回的 executeStepQueue 正常含 javax-pro 节点的 CmpStep
   （成功/失败、tag、异常均在），项目现有 buildResult/toNodeStep 取数路径可用。

**第二步在项目工程内待实测**（机制高信心，环境集成未亲验）：

1. Spring Boot LaunchedURLClassLoader 下，自研 ScriptAtomCompiler 调 Liquor DynamicCompiler
   时 TCCL 是否稳定可见平台类（探针是平面 classpath；打包成 fat jar 后以 TCCL 实测为准，
   不稳则显式传 DatabusNodeComponent.class.getClassLoader()，并在启动后预热编译一次）；
2. PostProcessNodeExecuteLifeCycle（执行记录落库钩子）对 javax-pro 节点的触发与 StepResultPayload
   摘要挂载是否与 Java 件完全一致（探针证实 CmpStep 数据在，项目钩子链未接入验证）；
3. 物料发布写 lf_script 后 Rule-DB 3s 轮询/60s 对账周期内的端到端生效时延与 last-good 行为；
4. 保存管线 ScriptAtomCompiler 编译期注解反射在真实 @DatabusCmp/@DatabusProp（含枚举默认值、
   options 动态数据源）下的 schema 完整度——第一步先在形态①SetValue 上验，第二步脚本入口复用。

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

> 详细设计（注解模型 / Schema 契约 / 反射扫描 / 合流接口 / SchemaForm / setValue 试点与推广批次）：
> [databus-schema-driven-form.md](databus-schema-driven-form.md)

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
- 注册式原子语言为 Java（javax-pro），保存管线＝编译校验（只编译不执行）→ 反射同套注解产出 param_schema → 人审 → 发布；安全管控落地人审/发布闸门，字节码黑名单扫描按需后补（设计与证据见 [databus-schema-driven-form.md §5.5](databus-schema-driven-form.md)）；
- 脚本原子宿主组件（§2.3 六件事）；
- 脚本原子编写规范与白名单工具库：Hutool、JSONPath 等**纯工具**可白名单开放；**不暴露 http/bpm 总线动作函数供脚本自由调用**——要碰外部系统就走注入句柄或独立原子，副作用一律上浮画布。
- 此后新原子一律脚本入库；httpRequest 存量迁移试点。

### 第三步：AI 原子物料生产线

端到端链路：读接口文档 → 查机器可读目录（`/options`）→ 盘点已有物料 → 补缺叶子脚本（入库为自定义原子）→ 生成 nodeTree/EL → 渲染画布 → 试运行校验 → **人审** → 发布 → 版本/回滚。模型接入支持私有化（Qwen/DeepSeek）。v1 边界：AI 只出草稿，人审核后才生效；CHAIN 子链路是多原子复用的既有机制，AI 同样遵守原子纯净规则。

---

## 5. 六维尽调结论

1. **技术可行性：高**。每一块都有主流产品先例（代码即 schema、脚本叶子、资源注入、MCP 查目录、分层校验）。
2. **安全：可控，但管控闸门必须先行**。脚本原子定为 Java（javax-pro，无字节码级沙箱），句柄注入与人审/发布闸门未落地前，不对外开放生产编辑（2026-10-02 修订，见 §2.3 与 schema 设计 §5.5）；Groovy 无沙箱红项仅限画布自由脚本节点。
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
