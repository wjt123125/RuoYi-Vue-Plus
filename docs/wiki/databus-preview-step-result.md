# 试运行步骤结果展示：人话摘要 + 数据明细

> 2026-09-19 拍板。主题：编辑器「试运行」结果弹窗的执行步骤表，成功行从"只有成功标签"升级为"一眼看懂这一步干了什么"。

## 1. 要解决的问题

步骤表原有 6 列（# / 数据空间 tag / 组件 nodeId / 结果成败 / 耗时 / 错误信息），节点成功时末列全空，看不出这一步**建了几个、删了哪些、影响了什么**。

定位区分两层，不许混：

| 层次 | 内容 | 位置 |
| --- | --- | --- |
| **执行结果（给人看）** | 组件自报的一句话：数量 + 关键标识 + 外部影响 | 表格末列 |
| **数据明细（给人查）** | 该节点名下 `$.<tag>` 子树的当场 JSON 快照 | 行点击滑出的抽屉 |
| 错误现场 | 红字错误文案（表格）+ 完整错误信息（抽屉） | 两层都有 |

弹窗底部「执行后上下文」全量 JSON 区保留，作为全局终局对照。

## 2. 后端采集机制

### 2.1 LiteFlow 升级 2.16.0 → 2.16.1.3

- 原因：全局节点执行监听器 `PostProcessNodeExecuteLifeCycle` 为 **v2.16.1 新增**，2.16.0 无此类。一个 Spring Bean 注册后覆盖**全部** NodeComponent，业务组件零改动；唯一不继承 `DatabusNodeComponent` 的 `ConditionComponent`（直接继承 `NodeBooleanComponent`）也能被覆盖。
- 实际采用 Maven Central 四位热修复版 **2.16.1.3**（2.16.1.x 线最新，含 #IK6XVN javax-pro ThreadLocal 泄漏修复）。
- 评估过但**不采用**的备选：覆写 `DatabusNodeComponent.afterProcess()`。2.16.0 源码已核实其在 `NodeComponent.execute()` 的 `finally` 中成败都回调、且早于 `cmpStep.setStepData(...)` 拷贝（2.16.0 sources `NodeComponent.java:143` 与 `:150`），是官方文档示例的正牌用法；但条件组件不在继承链内，需单点补代码。升级若受阻回退此方案。

### 2.2 监听器采集内容

新增一个实现 `PostProcessNodeExecuteLifeCycle` 的 Spring 组件（建议放 `org.dromara.databus.executor` 包），在 `postProcessAfterNodeExecute(cmp, timeSpent, e)` 中采集：

1. **数据明细**：`cmp.getTag()` 对应 `$.<tag>` 子树，**当场序列化成 JSON 字符串**。
   - 必须当场序列化、禁止存引用事后转：FOR/WHILE/ITERATOR 循环里同一 tag 会执行多轮，只有当场拍快照才能各保各的现场。
2. **人话摘要**：组件自报，见 §3。
3. 失败（e != null）同样触发（after 在 finally 回调），快照如实呈现半成品/空现场，错误另走 errorMessage。

**关键实现坑（2.16.1.3 sources 已逐行复核）**：`NodeComponent.execute()` 的 finally 中先执行 `cmpStep.setStepData(refNode.getStepData())`（约 :170），**之后**才回调 after 钩子（约 :187），钩子里直接 `cmp.setStepData(...)` 只写到 refNode，本次 CmpStep 拿不到。

最终落地（`NodeStepResultCollector` + `StepResultPayload`）：

1. **摘要载体**：`DatabusContext` 内一个 `ThreadLocal<String> stepSummary`（WHEN 并行线程隔离），组件经基类 `resultSummary(...)` 上报、钩子消费即 remove。
2. **挂载方式**：不落 attachment。`slot.getExecuteSteps()` 返回的是实时 `ConcurrentLinkedDeque<CmpStep>`（步骤在节点执行前约 :101-109 入队），钩子中用 `descendingIterator()` 倒序找「`instance == cmp` + threadName 匹配 + stepData 尚不是 payload」的第一步，直接 `step.setStepData(new StepResultPayload(summary, detailJson))`。三条件分别兜循环多轮、WHEN 并行、同节点串行 THEN(a,a)。
3. **布尔组件**：`NodeBooleanComponent` 不写数据空间，其产出是判定值，公开 API 为 `getItemResultMetaValue(slotIndex)`（返回 Boolean，循环 metaKey 天然带轮次）；明细固定序列化为 `{"conditionResult":true}`，摘要缺省「条件判定为 true/false」。
4. **全局钩子防误伤**：ruoyi-workflow 也跑 LiteFlow，钩子遍历 `slot.getContextBeanList()`（元素是 hutool `Tuple`，取 `tuple.get(1)` 判型；不能用 `getContextBean(Class)`，找不到会抛异常），无 DatabusContext 直接 return。
5. 组装：`DatabusExecutor.toNodeStep()` 从 `step.getStepData()` 识别 `StepResultPayload` 回填两字段；采集全程 try-catch，只记 error 不影响业务。

### 2.3 VO 变更

`DatabusExecutionResult.NodeStep` 增加：

- `String summary`：人话执行结果；
- `String detailJson`：名下子树当场快照 JSON。

`errorMessage / timeSpent / startTime / endTime` 保留不动；`PreviewRunVo` 透传 steps 不变。

## 3. 组件人话摘要约定

`DatabusNodeComponent` 开一个 protected 口子（如 `resultSummary(String text)`），各组件在 `process()` 收尾时把计数/标识在手里时报一句；**没报的兜底显示「完成」**。

风格：动宾 + 数量 + 关键标识，一条长中文 ≤30 字左右。

### 3.1 行为准则：只写表格上没有的增量信息（2026-09-19 拍板，写 comp 必守）

步骤表每行已有 **数据空间 tag、组件名、成败、耗时** 四列。摘要里一律禁止出现：

1. **成败词**：成功 / 失败 / 完成 / 已创建 / 已启动 等——「结果」列已表达；
2. **耗时**：「耗时 Nms」——「耗时(ms)」列已表达；
3. **tag 与组件注册名**——前两列已表达；
4. **无行动意义的流水信息**：如「响应 N 字节」——字节数既不说明对错也不驱动决策；响应内容去行点击的抽屉快照里查。

不违规的例外：**区分业务分支状态的词必须保留**，如 PROCESS_TERMINATE 的「此前已结束 / 终止未生效」（成功行也可能终止未生效）、TASK_COMPLETE 里统计任务的「成功 N 个 / 失败 M 个」（说的是任务不是节点）。

自检方法：**假设把这句摘要删掉，用户是否仍能从同一行知道成败与耗时？** 能，才说明这句写的全是增量信息；若删掉后没损失任何东西，这句就不该写（不调用 `resultSummary`，前端统一兜底「完成」）。

起因：HTTP_REQUEST 原报 `GET 200，响应 4575 字节，耗时 174ms`，后两截与列重复且字节数无意义；RESPONSE 原报 `组装流程响应：成功` 与「结果」列完全同义。

### 3.2 各组件实际落地文案

| 组件 | 摘要文案 |
| --- | --- |
| BO_CREATE | `新建 BO N 个：id1、id2，回写来源 M 条`（sampleIds 取前 2 条 record.ID；M 为各 rewrite 策略回写记录数之和，`applyRewrite` 改返回 int） |
| BO_QUERY | `查询 <boName>：命中 N 条`（list/listPage）/ `查询 <boName>：共 N 条`（count） |
| BO_UPDATE | `更新 BO N 条（<首个 boName>）`（N=ΣupdatedCount） |
| BO_DELETE | `删除 BO N 条（<首个 boName>）`（N=ΣremovedCount） |
| RDS_EXECUTE | getMaps `查询返回 N 行`；getMap `查询命中 1 行`/`查询未命中行`；update `更新影响 N 行`；batch `批量执行 K 条，影响 N 行`；标量 `取值（getX）：<值>`；返回形态异常时描述状态（`查询返回非列表结果`），不写「完成」 |
| HTTP_REQUEST | `GET 200`（方法 + 状态码；容错模式下 4xx/5xx 也可能是成功行，状态码是关键信息；不写响应字节数与耗时） |
| IDCARD_TO_USERID | `3 个身份证：命中 2，未命中 1`（跨 fields 聚合；多字段时追加 `（M 个字段）`） |
| FIELD_MAP | `搬运 6 个字段` |
| SET_VALUE | `赋值：<path>` |
| DATA_PATCH | `补丁命中 N 个对象，合并 M 个字段` |
| RESPONSE | 配置了业务消息 msg 时报 msg 原文；无 msg 不写（兜底「完成」） |
| SESSION_CREATE | `会话：<sessionId>` |
| PROCESS_START | `流程：<processInstanceId>`（有待办追加 `，待办 N 个`） |
| PROCESS_TERMINATE | 主流分支动宾报 `终止流程：<instanceId>`；外部返回的两个业务分支状态保留完成态词：`流程此前已结束：<id>` / `终止未生效：<id>`（§3.1 例外） |
| TASK_COMPLETE | `任务提交：成功 N 个`，有失败追加 `，失败 M 个`，processEnded 追加 `，流程已结束`（failOnError 抛错前也已上报） |
| CONDITION | `条件判定为 true` / `条件判定为 false`（采集器兜底，明细固定 `{"conditionResult":...}`） |

- **tag 外副作用不做自动路径收集**（曾评估 save() 出口统一收集外部写入路径清单的方案，否决：批量回写体积大、折叠复杂）；组件改了别人地盘时，在同一句摘要里自述，如 BO_CREATE 的 `，回写来源 M 条`。
- 共 15 个业务组件补 `resultSummary(...)`，CONDITION 不写代码、由采集器按布尔判定兜底。

## 4. 前端展示（plus-ui `views/databus/editor/index.vue` 试运行结果弹窗）

1. 末列「错误信息」改名「**执行结果**」：
   - 成功：显示 `summary`，单行截断、link 样式；
   - 失败：红色显示 errorMessage 原文（同样可点击）。
2. 点击单元格文本滑出 **el-drawer**（不新增列），自上而下：
   - 步骤元信息：数据空间 tag / 组件 nodeName/nodeId / 成败 / 耗时 / 起止时间（el-descriptions）；
   - 失败时：el-alert 完整展示 errorMessage 原文（后端 NodeStep 只携带异常 message，**不含堆栈**，堆栈只在服务端日志）；
   - 「数据明细」：复用 JsonCodeEditor 只读渲染 pretty 后的 `detailJson`；无快照显示 el-empty。
3. 行内截断由前端 CSS 处理（max-width 240px + ellipsis，非字符数截断）；后端快照不做体积截断。
4. 抽屉内不展示全量上下文——全局终局仍看弹窗底部原 JSON 区，定位不重叠。

## 5. 升级注意与验证边界

- EL normalize 空格/单引号 bug 在 2.16.1.3 仍存在，`DatabusExecutor.executeByEl` 的 `LiteFlowChainELBuilder` 绕行**已确认保留未动**（见 [liteflow-el-normalize-bug.md](./liteflow-el-normalize-bug.md)）。
- liteflow-metrics 风险已处置：2.16.1.3 的 starter 把 `liteflow-metrics` 作为**非 optional 编译依赖**引入（2.16.0 无此依赖），其 AutoConfiguration 上 `@ConditionalOnProperty(liteflow.metrics.enabled)` 缺省为 **true**，且 RuoYi classpath 上有 micrometer + actuator（ruoyi-common-web），`management.endpoints.web.exposure.include='*'` 会把 `/actuator/liteflow` 端点暴露出去，返回内容含 chain 元数据与 **EL 原文**。已在 `ruoyi-admin/application.yml` 显式设置 `liteflow.metrics.enabled: false`（指标采集与端点一并关闭）；将来接 Prometheus 时再开并同步收紧 actuator 暴露面。
- 升级后回归由用户亲自执行（AI 不代跑测试）：全部 mock 示例试运行（纯内存 / HTTP / BPM 各组件），重点 SQL 带空格字符串场景、带循环的示例验证多轮快照互不串台。
- 前端验收点：成功行行行有摘要、点开抽屉明细正确；失败行红字不回归；条件行显示「条件判定为 true/false」、抽屉明细为 `{"conditionResult":...}`。
- 静态校验已过：`mvnw compile`（ruoyi-databus 及上游模块）、oxlint 0 error、vue-tsc 仅剩 monitor/logininfo 两个历史 TS1149 基线报错。
