# 公式引擎调研报告：要不要做、业界怎么做、在我们架构里怎么做

> 阶段 1E 并行调研（2026-09-20）。起因：roadmap 阶段 1 列了「公式引擎研究：① 必要性论证 ② 现成方案调研 ③ 选型后再实施」。
> 本篇只做研究与拍板建议，**不含代码改动**。结论先行，证据在后。

## 1. 结论（TL;DR）

**当前阶段不自研、也不新引"公式引擎"。** 旧系统那套 `@公式(...)` 不做 1:1 迁移。维持我们已经在 [databus-script-component.md](databus-script-component.md) 拍板的双层能力模型，把它明确升级为**三层模型**：

1. **结构化原子组件**（condition 的 10 个操作符、setValue、fieldMap、各业务组件的结构化表单）——高频、稳定、零语法门槛；
2. **脚本节点**（Groovy，已上线）——长尾、不规则、多行逻辑；
3. **参数槽内联表达式**（缺口，暂不补）——一行算术/三元/字符串函数/复合条件。

第三层**确实存在能力缺口**，但触发条件不成立（真实调用密度未被证实、长尾已被 Groovy 兜住），所以现在不做。将来要做时：

- 引擎选 **QLExpress4**——它已经随 `liteflow-core:2.16.1.3` 在我们 classpath 里（本地 m2 实测 `com.alibaba:qlexpress4:4.1.0`），零新增依赖，与 LiteFlow EL 同源，Apache-2 协议；
- 语法走 n8n 的 `=` 前缀约定（如 `= $.amount * 0.8`），求值落点是 [DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java)，不碰 EL；
- 或者更"LiteFlow 原生"的路线：加 `liteflow-script-qlexpress` 插件，把表达式当**表达式脚本节点**用，仍走现有脚本节点注册通道，不自己写引擎胶水。

触发信号、完整设计、备选方案见 §6、§7。

---

## 2. 旧系统公式机制盘点

### 2.1 它是什么

旧系统（BPM 端 data.bus app）的参数值以 `@` 开头即被识别为公式，在 [DataTypeProcessor.processFormula](file:///e:/01.code/Actionsoft/release/src/com.awspaas.user.apps.data.bus/src/com/awspaas/user/apps/data/bus/util/DataTypeProcessor.java) 里先做混合路径替换，再调 **AWS PaaS 平台的规则引擎**执行：

```java
SDK.getRuleAPI().executeAtScript(resolvedFormula, userContext, processInstance, taskInstance, new HashMap<>())
```

也就是说，旧系统自己**没有公式引擎**，公式的解释器、函数库（`@Contains`、`@Equals`、`@WebService` 等）、嵌套调用（`@Contains(@userIdByIdCard(...), 'x')`）全部是 BPM 平台自带的能力。项目侧只通过继承平台 `AbstExpression` 注册了 5 个自定义函数（[DataBusPluginListener](file:///e:/01.code/Actionsoft/release/src/com.awspaas.user.apps.data.bus/src/com/awspaas/user/apps/data/bus/config/plugin/DataBusPluginListener.java)），并由 `DynamicJSONBuilder` 在构建 JSON 参数时递归求值（JSON 对象任意层级的字符串值都可能是公式）。

### 2.2 五个自定义公式在新系统的归宿

| 旧公式 | 干什么 | 新系统归宿 |
| --- | --- | --- |
| `@stringCopies(n, el, sep)` | 生成 `?,?,?` 这类 SQL 占位符 | **已淘汰**：rdsExecute 用 List 参数 + `DynamicBatchSetter` 批量绑定，占位符由 PreparedStatement 处理 |
| `@userIdByIdCard(idCards)` | 身份证批量查用户 ID | **已有原子组件** idCardToUserId（BPM IDCARD_TO_USERID 端点，支持多字段/分隔符/全未命中抛错） |
| `@userNameByIdCard(idCards)` | 身份证批量查用户名 | 同上，端点返回 userIds；若确有 userName 诉求应扩端点返回字段，而不是恢复公式 |
| `@formatTime(pattern, ts)` | 时间格式化 | Groovy 脚本一行 `new Date().format('yyyyMMdd')`（可直接调 hutool）；脚本规格档已明确"时间戳转日期这类冷门 convert 不出组件，由脚本承接" |
| `@isNotBlank(v, t, f)` | 非空三元 | condition 组件的 `notBlank` 操作符 + IF 分支；或未来内联表达式 `= $.x != nil ? '是' : '否'` |

**代码仓内这 5 个公式零调用**——它们的真实调用点在 BPM 平台的配置数据里（BO_EU_API_* 三层表的参数字段值），不在代码仓库。平台自带公式（`@Contains` 等）在真实配置里的使用密度，我们目前没有数据，要等「旧 meta 翻译工具」摸底时统计。这是必要性判断里最大的未知数，也是现在不该拍脑袋建引擎的原因之一。

### 2.3 旧机制的本质问题

- 引擎是平台的，解耦 BPM 后这条能力整条消失，不是"搬走"能解决的；
- `@xxx(...)` 是一套私有 DSL，函数注册靠 Java 类 + 插件清单，加函数要发版，业务人员读不懂嵌套调用；
- 任意字符串值都可能被求值，隐式、递归、出错只返回 `"@func(执行失败)"` 字符串（不中断），排障困难。

## 3. 新系统现状：已经有哪些"表达能力"

按能力粒度从粗到细：

1. **参数解析三件套**（[DatabusContext.resolve](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/DatabusContext.java) + [PathResolver](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/context/PathResolver.java)）：纯路径整取（`$.a.b`）、混合模板替换（`用户${$.name}`）、循环索引（`$i`）。只做**取值和字符串拼接**，不能计算。
2. **结构化条件组件** [ConditionComponent](../../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/component/logical/ConditionComponent.java)：isTrue/isNull/notBlank/eq/ne/gt/ge/lt/le/contains 共 10 个操作符，数值按 BigDecimal 比较。表单配置，无语法。
3. **Groovy 脚本节点**（script / booleanScript，2026-09-20 用户实测通过）：任意逻辑，可读全部 Java/hutool 类，能写多行、能定义函数。
4. **LiteFlow 编排 EL**：IF/WHEN/SWITCH/FOR/ITERATOR + 复合 AND/OR/NOT/CATCH——但它表达的是"节点怎么编排"，不是"数据怎么算"。

**真实缺口**只在第 2 和第 3 层之间：参数槽里一行就能写完的计算，现在没有位置放。例如：

- HTTP body 里要写 `"discountPrice": 上游金额 × 0.8`——只能拖一个 Groovy 节点先算好再引用，为一行算式占用一个画布节点 + 一个数据空间；
- condition 想要 `$.a > 10 && $.b < 5` 这种跨字段复合条件，现在要用 IF/AND/OR 网关组合（可以做，但重）；
- setValue 想写 `$.name + '-' + $.code` 拼接（混合模板能拼接，但没有 trim/upper/日期函数）。

这个缺口的形态，业界有标准答案：**内联表达式**（inline expression），不是"公式引擎"。

## 4. 业界怎么做

### 4.1 主流编排/iPaaS 产品：清一色三层结构

| 产品 | 结构化层 | 内联表达式层 | 代码层 |
| --- | --- | --- | --- |
| **n8n** | Set/Aggregate/Sort 等转换节点、IF 节点 | 参数槽 `{{ }}`：受限表达式，可引用任意上游节点输出、内置 Luxon 日期/数学方法、编辑器自动补全；只能写**单个表达式**不能写语句 | Code 节点（JS/Python 任意代码） |
| **Node-RED** | Change 节点（set/change/delete/move）、Switch 节点 | Change/Switch 的值槽可切到 **JSONata**（声明式 JSON 查询转换语言，内置 sum/map/filter 等），官方定位"不懂 JS 的人做简单转换" | Function 节点（任意 JS） |
| **Camunda/Activiti（BPM 标准）** | 输入/输出参数映射（input/output mapping 表） | **JUEL**（Java 统一表达式语言）`${amount > 100}`，参数映射和序列流条件里随处可写 | 脚本任务（Groovy/JS） |
| **MuleSoft** | 各类连接器 | **DataWeave**（专门的 JSON/XML 转换语言，强在报文重塑） | Groovy/Java 组件 |

官方文档的原话很能体现分层哲学。n8n 数据指南把转换手段并列成四种（转换节点 / expressions / Code 节点 / AI Transform），并在表达式编辑器里只做表达式不做语句；Node-RED 文档说 Function 节点"gives you complete flexibility, but does require familiarity with JavaScript and is unnecessary for many simple cases"，简单场景交给 Change + JSONata。

**没有一家主流产品是"自研一套 @函数注册表 DSL"的路子。** 共同点是：结构化表单覆盖 80% 无语法场景；声明式表达式补 15% 的一行计算；完整代码兜 5% 的长尾。三层各有明确门槛和位置，表达式引擎对用户是**内嵌的语言**，不是一个需要感知和注册函数的"系统"。

另一个相关案例：我们自己的迁移经验（成本控制系统 LiteFlow 化）中，定价/结算价公式最终也是落到 **LiteFlow 脚本节点（Aviator 语言）+ 脚本资产管理 UI**，复杂公式按脚本资产管，而不是在参数字段里塞私有公式语法。

### 4.2 JVM 表达式引擎候选横评（2026-09 实测时间点）

| 引擎 | 版本/活跃度 | 协议 | 定位 | 与我们的关系 |
| --- | --- | --- | --- | --- |
| **QLExpress4** | 4.1.0（2025-02 重写发布，4.1.2 2026-06 仍在迭代）；阿里官方复活维护，编译性能较 3.x 提升 10 倍、原生 JSON 字面量、动态字符串 `${expr}`、表达式追踪 | Apache-2.0 | 业务规则/表达式，语法接近 Java，支持自定义函数/操作符 | **已在 classpath**：`liteflow-core:2.16.1.3` pom 明确依赖 `com.alibaba:qlexpress4`（本地 m2 为 4.1.0）。LiteFlow 的 EL 解析内核就是它 |
| **AviatorScript** | 5.4.3（2024-06，稳定多年）；个人维护 15 年，ASM 字节码高性能，5.4.3 起带安全沙箱开关 | **LGPL**（注意：对企业闭源分发有合规审查成本，动态链接通常可用但需法务确认） | 高性能表达式 + 脚本，内置丰富字符串/数学/序列函数 | LiteFlow 有官方脚本插件 `liteflow-script-aviator`；LGPL + 个人维护 + 需新增依赖，综合劣于 QLExpress4 |
| **SpEL** | 随 Spring | Apache-2.0 | Spring 生态属性导航/注解条件 | 已在 classpath；但它强在 Bean 属性导航与注解，**弱在业务函数库和安全沙箱**，默认可调任意方法（`T(Runtime).exec(...)`），放开给配置用户风险大 |
| **MVEL** | 2.x 长期停滞，3.0 还是 alpha | Apache-2.0 | 表达式+模板 | 不活跃，排除 |
| **JSONata4Java** | IBM 维护的 Java 端口，2.6.x（2025 年还在修安全 CVE） | MIT | JSON 查询/重塑专用 | 强在报文结构转换（对应我们 fieldMap/dataPatch 的长远方向），算术/业务规则表达别扭；且 2025 年有"用户提供表达式导致资源耗尽"类 CVE，安全模型同样要自己建 |
| **Groovy** | 已在用 | Apache-2.0 | 完整脚本语言 | 三层模型的"代码层"，不适合下沉为参数槽表达式（语法重、无沙箱） |

安全方面有个共性事实：**任何"执行用户编写表达式"的引擎都是代码注入面**（JSONata 2025 年的资源耗尽 CVE、QLExpress/Aviator 的反射调用能力都一样）。我们当前是可信内网用户场景，与 Groovy 脚本节点同等风险等级，可接受；对外开放/多租户前必须统一解决（函数白名单、禁反射与系统调用、超时），这不是选哪个引擎能绕开的。

## 5. LiteFlow 的设计怎么看这件事

### 5.1 官方哲学：Java 组件 + 脚本组件 + EL，各管一层

LiteFlow 官方脚本文档明确推荐组合是 **「Java 类组件 + 脚本组件 + EL 表达式」**，固定逻辑用 Java、经常变的小部分逻辑用脚本、EL 只负责编排。我们三层模型里的第 1、2 层与这个哲学完全对齐：原子组件 = Java 类，Groovy 节点 = 脚本组件。

### 5.2 EL 不能当业务表达式用

LiteFlow 的 EL（THEN/IF/SWITCH…）内核确实是 QLExpress4，但语义是**节点编排 DSL**——EL 里的标识符解析为 FlowBus 中的 Node/Chain 对象，不是数据变量。绝不能在业务参数里写 EL 片段求值：一是语义不对，二是有前科——2.16.x 的 `ElRegexUtil.normalize` 会篡改字符串字面量（删空格、单引号转双引号，见 [liteflow-el-normalize-bug.md](liteflow-el-normalize-bug.md)），我们试运行已经被迫绕开 `execute2RespWithEL`。业务表达式与编排 EL 必须物理隔离。

### 5.3 参数 data 是不透明字符串，求值不求值是组件的自由

画布参数经 `.data("...")` 进 EL，LiteFlow 把它当作不透明字符串（`_meta.cmpData` 可取），**框架不在参数层做任何表达式求值**。这意味着：在 `DatabusContext.resolve` 里加一个表达式分支，完全在我们自己的契约内，不违背 LiteFlow 设计，也不与未来版本升级冲突。

### 5.4 表达式语言 LiteFlow 已铺好官方通道

LiteFlow 脚本插件 8 种语言里本来就有 `liteflow-script-qlexpress` 和 `liteflow-script-aviator`。若要走"表达式即节点"路线（每个表达式是一个脚本节点），加一个插件依赖、复用 `LiteFlowNodeBuilder` 注册通道即可，不用自己粘引擎、不用自己管编译缓存。但它的粒度是**节点**，补的是"轻量脚本节点"，补不了"参数槽一行表达式"——后者必须在我们自己的 resolve 入口做。

## 6. 推荐方案（将来触发后实施）

### 6.1 触发信号（满足其一再开工，现在不做）

1. 旧 meta 翻译工具摸底 BO_EU_API_* 配置后，发现平台自带公式（`@Contains/@Equals/@formatTime` 等）在真实链路中**高频出现且翻译为 Groovy 节点明显臃肿**；
2. 用户在实际配置中反复遇到"一行计算要拖一个脚本节点"的摩擦（预期会在金额计算、复合条件、字符串函数三类场景出现）；
3. condition 组件结构化表单挡不住复合条件需求（AND/OR 网关组合被证明太重）。

在那之前，缺口由 Groovy 脚本节点兜，文档里写清用法即可。YAGNI：引擎接入虽小（引擎已在手），但表达式语法一旦发布就是**对外契约**，前端编辑器、错误提示、函数文档、后续兼容都要跟着养，晚定比早定便宜。

### 6.2 若做：QLExpress4 内联表达式，最小设计

**语法约定：`=` 前缀**（n8n 风格，也是 n8n 里从固定值切到表达式的官方交互）。不沿用旧 `@`（避免与 BPM 平台语义混淆、避免迁移期歧义），也不用 `${}`（我们已把 `${$.path}` 定义为纯路径模板，混用会让一个字符串里出现两套求值期）。

```text
= $.amount * 0.8                         // 算术
= $.a > 10 && $.b < 5 ? 'both' : 'no'    // 逻辑 + 三元
= string.trim($.name) + '-' + $.code     // 白名单函数 + 拼接
= $.list.size()                          // 集合
```

**求值落点**：`DatabusContext.resolve(Object)` 增加一个分支，分发顺序为：

```text
整串 $i（循环下标） → $. 纯路径 → "=" 前缀表达式 → 含 $. 的混合模板 → 字面量原样
```

关键约束：

- 表达式内的 `$.path` 在喂给引擎前先由我们自己解析（复用循环变量替换 + jayway 读取），**不把 JSONPath 交给 QLExpress**——路径语义只此一家，避免双引擎对 `$` 理解不一致。实现上可以把表达式中的裸路径片段替换为绑定变量（`_v0`、`_v1`…）连同值 Map 传给引擎；
- 引擎实例单例、开启编译缓存；只暴露**函数白名单**（一期建议仅 string 常用函数 + 日期格式化 + math，对着旧公式和实际需求长），关闭 new/反射/系统访问，设执行超时（QLExpress4 的超时/安全选项以官方 API 为准，实施前需写 demo 核实）；
- 表达式求值失败 = 节点失败，报清楚错误（表达式全文 + 引擎消息），不允许旧系统"返回一个失败字符串继续跑"的反模式；
- WHEN 并行安全：求值是纯函数（只读上下文文档 + 入参），无线程状态。

**条件组件扩展（可选、同期）**：ConditionCfg 增加 `expression` 字段，与 path/op/value 互斥，值为 `= ...` 的布尔表达式；booleanScript 的轻量替代。前端条件槽选"表达式"时出单行输入框。

**前端**：所有"值"类输入框支持 `=` 开头即按表达式高亮（一期单色高亮 + 错误提示即可，不做自动补全）；试运行步骤结果里表达式节点/字段照常出快照。自动补全（上游数据空间路径、函数签名浮层）列入三期，参照 n8n 表达式编辑器，是体验投入大头，不在最小版。

**混合模板一期不嵌表达式**：`金额= $.x * 0.8 元` 这种不支持，只支持整串 `=`。需要文案拼接时用表达式内字符串拼接。这条刻意保守，防止一套字符串里混路径模板 + 表达式两个求值期。

### 6.3 旧 meta 迁移期的映射策略

翻译工具遇到旧公式时分类处理：5 个自定义公式按 §2.2 归宿表映射为组件/脚本；平台自带公式中，简单比较类映射为 condition 结构，复杂的先翻译为 Groovy 脚本节点（语义 100% 可保），等翻译工具跑出真实频度统计后，再决定哪些高频函数值得进表达式白名单。**用真实数据驱动白名单，而不是凭记忆搬平台函数库。**

## 7. 备选方案与取舍

| 方案 | 评价 |
| --- | --- |
| **A. 推荐：暂不做，触发后 QLExpress4 内联表达式** | 零新依赖（已在 classpath）、与 LiteFlow 同源、Apache-2、语法近 Java 学习成本低；契约晚发布，主动权在手 |
| B. 现在就把第三层补齐 | 技术上只需一两天，但缺真实需求样本，语法/函数库容易拍脑袋，且要背前端编辑器和文档的长期维护，性价比低 |
| C. 加 `liteflow-script-qlexpress` 插件，表达式做成轻量脚本节点 | 最"LiteFlow 原生"，复用注册/热刷新/校验全套设施；但粒度是节点，解决不了"参数槽一行计算"，画布会被小表达式节点淹没。可作为 6.2 的**互补项**：复杂但仍是纯表达式的逻辑（多行、可复用）用 qlexpress 脚本节点，一行计算用内联表达式 |
| D. Aviator | 性能与函数库优秀，但 LGPL 协议、个人维护、需新增依赖；除非 QLExpress4 实测安全/能力不达标，否则不选 |
| E. JSONata | 报文重塑场景的远期候选（fieldMap/dataPatch 增强），不适合作为通用业务表达式，不解决当前缺口 |
| F. 自研 @函数注册表（旧系统形态） | 直接排除：私有 DSL、发版才能加函数、隐式递归求值、与业界三层模型背道而驰 |

## 8. 对 roadmap 的回填建议

- roadmap 阶段 1「公式引擎研究」事项：**调研结论 = 不建独立公式引擎**，按三层模型演进，本报告存档即该事项的产出；
- 阶段 4「灵活执行逻辑完善」时，若 §6.1 触发信号出现，内联表达式作为 condition/参数表单的增强项进入，不单独开"公式引擎"工程；
- 安全闸门：表达式与 Groovy 脚本同属"用户编写并在服务端执行"的能力，在对外开放/多租户前统一过一次沙箱评审（函数白名单 + 反射禁用 + 超时 + 资源限制），GraalVM 沙箱是两条线共同的备选方案（脚本规格档 §7 已记录）。

## 9. 参考来源（2026-09-20 核实）

- 本地实证：`liteflow-core-2.16.1.3.pom` 依赖 `com.alibaba:qlexpress4`，本地 m2 版本 4.1.0；`liteflow-script-groovy:2.16.1.3` 已装；未装 qlexpress/aviator 脚本插件
- LiteFlow 官方文档（v2.16.X 脚本组件章节，本地 skill 留档）：8 种脚本语言、4 种脚本节点类型、Java 组件 + 脚本 + EL 推荐组合、ScriptValidator 热校验
- n8n 官方文档 docs.n8n.io（Data / Expressions / Code node）
- Node-RED 官方文档 nodered.org（Working with messages、Writing Functions）
- QLExpress4：GitHub alibaba/QLExpress（2025 年重写发布，4.1.x 持续迭代）；mvnrepository com.alibaba:qlexpress4
- AviatorScript：GitHub killme2008/aviatorscript，5.4.3（2024-06）；mvnrepository（协议 LGPL）
- JSONata：jsonata.org；JSONata4Java com.ibm.jsonata4java（IBM，2025 年安全公告记录其表达式资源耗尽类 CVE）
- 旧系统源码：data.bus app 的 config/formula 包、util/DataTypeProcessor.java、config/plugin/DataBusPluginListener.java
