# BPM Connector 端点 Service 设计

> 模块：1D-P0 BPM 端总线 app（新建独立仓库）
> 主题：10 个 mapping 端点的 service 入参/出参 schema、公共基类、老系统取舍
> 更新：2026-09-19（§17 增补 RDS_EXECUTE / IDCARD_TO_USERID 两批次端点）

## 1. 背景与问题

1D-P0 阶段迁移 BPM 三件套（session_create / bo_create / process_start）+ task_complete 到新总线 ruoyi-databus。架构决策已拍板采用 Connector 模式（JDBC 范式——总线下命令、BPM 自实现），并在 BPM 端新建独立仓库部署总线 app，提供 4 个独立 mapping 端点（模式 A：URL 即操作）。

本文档讨论并沉淀：4 个端点对应 service 的入参/出参 schema、公共基类设计、老系统合理点 vs 不合理点、关键设计决策。

架构决策原文见 `project_memory.md` §Tech Choice Decisions「1D-P0 Connector 架构决策（2026-09-17 拍板）」。

## 2. 架构定位与原子化原则

### 2.1 三层物料分层与职责边界

| 层 | 职责 | 不做 |
|---|---|---|
| **Connector 层**（RuoYi-Vue-Plus ruoyi-databus） | 持有 Connection（endpoint/auth/超时/重试）、接收 Component 层传入的"已准备好的请求 DTO"、HTTP 调用 BPM 端端点、超时/重试/错误码转换、返回响应 DTO | 字段重映射、类型转换、回写策略、路径读取 |
| **Component 层**（LiteFlow 组件） | 业务编排：从 dataSpace 读数据、字段重映射（fieldMap 组件）、回写策略执行、调 Connector | 协议细节、连接管理 |
| **BPM 端总线 app**（新建独立仓库） | 原子能力暴露：4 个独立 @Mapping 端点，每个端点只做一件事，入参完全自包含，不依赖任何共享上下文 | 字段重映射、回写策略、上下文路径读取 |

### 2.2 原子化原则（核心）

旧系统 processor 能用 `$.jsonpath` 从 document 上下文读路径值，是因为 document 是 BPM 容器内的内存对象。新系统上下文已分离到总线系统（RuoYi-Vue-Plus ruoyi-databus，独立于 BPM），BPM 端总线 app 没有这个 document。

因此 BPM 端每个端点的入参必须是**完全自包含的数据包**——所有需要的值都从 HTTP 请求体显式传入，不能有任何"按路径从上下文读"的隐式取值。这是真正的原子化：每个端点只做一件事，入参完全自包含。

旧系统 BO_CREATE 的实际数据流印证了这点：
```java
// 旧系统：meta 里只有路径配置，实际数据从 document 现读
String path = String.format("$.%s", rawPath);
List<JSONObject> rawDataList = document.read(path, ...);  // 从内存上下文按路径读
for (JSONObject rawData : rawDataList) {
    BO bo = createBOByConfig(rawData, allFieldConfig);     // 字段别名/类型转换
}
```
新系统 BPM 端 BO_CREATE 不再有 document，records 数据直接由请求体传入。

## 3. 老系统分析

### 3.1 SDK API 关键签名（已核实 BPM 文档）

- `SDK.getPortalAPI().createClientSessionByDevice(userId, password, lang, clientIp, device)` → JSON 字符串 `{result, msg, data:{sid}}`
- `SDK.getBOAPI().create(boName, boList, bindId, uid)` / `createDataBO(boName, boList, userContext)` → `int[]`，ID 写回 `bo.getId()`
- `SDK.getProcessAPI().createBOProcessInstance(processDefId, uid, title)` → ProcessInstance；`.start(processInstance)` → ProcessExecuteQuery
- `SDK.getTaskQueryAPI().processInstId(id).activeTask().list()` → List<TaskInstance>；`SDK.getTaskAPI().completeTask(task, userContext)` → void
- `UserContext.fromUID(uid)` 构造用户上下文
- `SDK.getProcessQueryAPI().detailById(id)` → ProcessInstance；`ProcessInstance.isEnd()` 判断结束
- `BO.set(key, value)` 的 value 是 Object 类型——**SDK 不做类型转换，传什么存什么**

### 3.2 老 system processor 入参契约

- **BpmCreateSessionProcessor**：`userName / password / clientIp / ipWhiteList` → 输出 `sessionId / idCard`。查身份证号（`getIdCardByUserIds`，项目自定义工具查 BO 用户档案）→ 查用户绑定 IP（`getAllowedIpByUserId`，项目工具）→ 合并配置白名单 → 校验 clientIp → 调 SDK → 解析 JSON 判断 `result=="ok"`
- **BoCreateProcessor**：`meta`（List<JSONObject>，含 `name/path/fieldConfig/rewrite`）+ `method` + `bindId`。meta 里只有路径配置，实际数据 `document.read("$.path")` 现读。6 种回写策略（no/all/boId/add/exclude/include）回写到 document 的 JSONPath。method=create 时 bindId 必填，method=createDataBO 时不需要 bindId
- **ProcessCreateProcessor**：`def.id / user.id / title.template / task.autocomplete`。构造 UserContext → createBOProcessInstance → start → 若 `isProcess()` 且 `task.autocomplete!=false`，查活跃任务并 completeTask。输出 `result.processInstanceId`
- **TaskCompleteProcessor**：`processInstanceId`。detailById 查实例 → isEnd() 判断 → 查活跃任务 → 逐个 completeTask（异常吞成 warn）

### 3.3 合理点（可参考）

1. 入参路径化（`def.id / user.id / title.template`）语义清晰，新系统继承为 DTO 字段名（去点分改驼峰）
2. BO 双 method 设计（create 流程驱动需 bindId / createDataBO 纯数据需 UserContext）覆盖两类场景
3. 6 种回写策略覆盖常见回写需求，add 策略支持按字段单独回写到数组指定索引
4. process_start 后 isProcess 判断区分"真流程"vs"仅控制记录"，避免对非流程任务误调 start
5. TaskComplete 先查后判断（detailById + isEnd）避免对已结束流程无效 complete
6. 日志埋点丰富（info/warn/error/success 多级别）

### 3.4 不合理点（需规避）

1. **错误吞掉**（最严重）：BpmCreateSession 多处 `return;` 静默失败——IP 不在白名单、SDK 异常、`result!=ok` 都是 return，调用方无法判断成败。新系统必须抛异常或返回错误码
2. **task.autocomplete 混入 process_start**：ProcessCreateProcessor 既创建流程又自动完成任务，职责不单一。新系统 task_complete 独立为端点，process_start 保持纯创建+启动
3. **强耦合 BPM 框架**：BaseProcessor 继承 OperationContext（BPM 框架类），processor 直接依赖 SDK + 项目工具方法，无法脱离 BPM 容器测试。新系统 Service 是普通 Java 类，只通过 SDK 调 BPM
4. **反射工厂固定包路径**：OperationProcessorFactory 写死 `PROCESSOR_PACKAGE`，扩展性差。新系统走模式 A，4 个独立 @Mapping，不需要工厂
5. **回写耦合 document**：BoCreateProcessor 回写目标是 BPM 框架的 document 对象。新系统 BPM 端没有 document，回写策略不在 BPM 端做（详见 §4 决策）
6. **成员变量状态**：processInstance/executeQuery 作为 OperationContext 成员变量，模式不优雅。新系统 Service 无状态，局部变量传参
7. **缺校验**：process_start 不校验 def.id/uid 是否为空。新系统入口做参数校验
8. **缺超时/重试**：所有 SDK 调用无超时控制。新系统在 Connection 配置层做（不在 BPM 端，在 Connector 层）
9. **completeTask 异常吞成 warn**：TaskCompleteProcessor 对单个任务失败只 warn 不终止，部分成功部分失败状态不一致。新系统明确"全部成功 vs 部分成功"语义
10. **依赖项目自定义工具方法**：`getIdCardByUserIds / getAllowedIpByUserId` 耦合 BPM 系统 BO 表结构。新系统用 SDK API 重写，不依赖项目工具

## 4. 4 个端点入参/出参 schema

### 4.1 SESSION_CREATE

入参 `SessionCreateRequest`：
```json
{
  "userName": "admin",      // 必填，BPM 用户 ID
  "password": "xxx",        // 必填
  "clientIp": "0.0.0.0",    // 可选，默认 0.0.0.0
  "lang": "cn",             // 可选，默认 cn
  "device": "PC"            // 可选，默认 PC
}
```

出参 `data`：
```json
{
  "sessionId": "xxx",
  "idCard": "xxx"           // 身份证号（查 BPM 用户档案，详见 §6 决策 1）
}
```

实现要点：校验 userName/password 非空 → 查 idCard（用 SDK 重写，不依赖项目工具）→ IP 白名单校验（ipWhiteList 由请求体传入，详见 §6 决策 1）→ 调 `createClientSessionByDevice` → 解析 JSON，`result!=ok` 抛异常 → 返回 sessionId + idCard。

### 4.2 BO_CREATE

入参 `BoCreateRequest`：
```json
{
  "method": "create",       // 可选，默认 create；create / createDataBO
  "bindId": "xxx",          // method=create 必填
  "uid": "admin",           // 必填
  "boList": [               // 必填
    {
      "boName": "BO_TEST",  // 必填
      "records": [          // 必填，已是 BPM 字段名 + 正确类型（字段重映射在 Component 层 fieldMap 完成）
        {"NAME": "x", "AGE": 1}
      ]
    }
  ]
}
```

出参 `data`：
```json
{
  "boResults": [            // 与入参 boList 顺序对应
    {
      "boName": "BO_TEST",
      "records": [          // 创建成功后的记录（含生成的 ID）
        {"ID": "001", "NAME": "x", "AGE": 1}
      ],
      "createdCount": 1
    }
  ]
}
```

实现要点：校验 boList 非空 → method=create 时校验 bindId → 构造 `List<BO>`，`bo.set(fieldName, rawValue)`（SDK 不做类型转换，类型由 Component 层保证）→ 按方法调 `create` 或 `createDataBO` → 收集结果（含 ID）→ **不做回写**（回写策略在 Connector 层，详见 §6 决策 2）。

### 4.3 PROCESS_START

入参 `ProcessStartRequest`：
```json
{
  "processDefId": "xxx",    // 必填
  "uid": "admin",           // 必填
  "title": "采购申请-2024"   // 必填
}
```

出参 `data`：
```json
{
  "processInstanceId": "xxx",
  "isProcess": true,
  "activeTaskIds": ["t1"]   // 启动后活跃任务 ID（供调用方决定是否调 TASK_COMPLETE，详见 §6 决策 4）
}
```

实现要点：校验三字段非空 → `UserContext.fromUID(uid)` → `createBOProcessInstance` → `isProcess()` 为 true 才 `start` → 收集 `fetchActiveTasks()` 的 ID → **不做 autocomplete**（task_complete 是独立端点）。

### 4.4 TASK_COMPLETE

入参 `TaskCompleteRequest`：
```json
{
  "processInstanceId": "xxx", // 必填
  "uid": "admin"              // 必填
}
```

出参 `data`：
```json
{
  "processInstanceId": "xxx",
  "processEnded": false,      // 提交后流程是否结束（可选，需二次 detailById 查询）
  "completedTaskIds": ["t1", "t2"],
  "failedTaskIds": [],        // 部分失败时记录（详见 §6 决策 3）
  "failedErrors": []          // 失败原因，与 failedTaskIds 对应
}
```

实现要点：校验 processInstanceId 非空 → `detailById` 查实例，null 抛异常 → `isEnd()` 为 true 返回"已结束" → `UserContext.fromUID(uid)` → 查活跃任务 → 逐个 `completeTask`，失败记录到 failedTaskIds（不吞异常）→ 返回结果。

## 5. 公共基类设计

`BpmBaseService`（抽象类，不继承任何 BPM 框架类）：

- **模板方法** `protected <T> ResponseObject doExecute(T request, String operation, Supplier<Object> action)`：统一 try-catch + 日志埋点（入参/出参/耗时）+ 错误转换。子类只传 action
- **响应构建** `success(Object data)` / `error(String code, String msg)`
- **用户上下文** `protected UserContext buildUserContext(String uid)`：封装 `UserContext.fromUID`，uid 为空时抛异常
- **异常** `protected void throwBpmError(String code, String msg, Object... args)`：记录 ERROR 日志 + 抛 `BpmConnectorException`
- **参数校验** `requireNonBlank(String value, String fieldName)` / `requireNonNull(Object value, String fieldName)`

4 个 Service 继承 `BpmBaseService`，各自实现 `execute(XxxRequest req)`，内部调 `doExecute` 模板。Controller 层 4 个 `@Mapping` 方法只做 HTTP 入参绑定 + 调对应 Service。

## 6. 关键设计决策

### 决策 1：上下文原子化（已拍板）

BPM 端总线 app 没有共享上下文，每个端点入参完全自包含。旧系统"从 document 按 $.jsonpath 读路径值"的模式不适用，所有值必须从 HTTP 请求体显式传入。这是真正的原子化。

### 决策 2：回写策略归属（已拍板）

6 种回写策略（no/all/boId/add/exclude/include）不在 BPM 端做，搬到 RuoYi-Vue-Plus 的 Connector 层。BPM 端 BO_CREATE 只返回创建结果（含生成的 ID），Connector 层拿到响应后按策略写 dataSpace。理由：BPM 端没有 document，回写是 Connector 层业务逻辑。

### 决策 3：fieldConfig 字段重映射归属（已拍板）

字段重映射（alias + type 转换）不在 Connector 层做（Connector 是协议层，不管数据形状），也不在 BPM 端做。归属 Component 层，通过复用/增强已有 fieldMap 组件实现：在 BO_CREATE 组件之前接一个 fieldMap，把 dataSpace 里的数据按 alias/type 配置重映射好，BO_CREATE 组件直接读映射后的数据。

理由：fieldMap 是通用能力，其他组件（BO_UPDATE / HttpRequest body 构造等）都能复用；Connector 层只管协议，不掺业务数据加工。

### 决策 4：SDK 类型转换事实（已确认）

`BO.set(key, value)` 的 value 是 Object 类型，SDK 不做类型转换，传什么存什么。因此 type 转换必须在 Component 层（fieldMap 增强）做，不能依赖 SDK 容错。

### 决策 5：fieldMap 增强方向（已定 A3）

现有 fieldMap 能力：单字段搬运，原值复制，不支持 alias（靠路径末段间接实现）、不支持 type 转换、不支持批量数组记录处理。

增强梯度评估：
- A1（最小）：不增强，依赖 SDK 容错——**已排除**（SDK 不容错，BO.set value 是 Object）
- A2（中等）：增强支持数组批量搬运（通配符路径 `$.orders[*].NAME`），不做 type 转换——JSON 本身有类型，多数场景够用
- A3（完整）：增强支持数组批量搬运 + type 转换（加 `type: "int"/"string"/"boolean"` 配置，不配 type 就原值搬运）

**已定 A3**：数组批量搬运 + type 转换作为可选配置（不配 type 就原值搬运，向后兼容）。

## 7. 最终决策（2026-09-17 全部拍板）

| # | 问题 | 决策 |
|---|---|---|
| 1 | SESSION_CREATE 是否保留 IP 白名单 + idCard 查询 | 保留：ipWhiteList 由请求体传入（Connector 从 Connection 配置带过来），idCard 用 SDK 重写查询（不依赖项目工具） |
| 2 | TASK_COMPLETE 部分失败语义 | 全部尝试 + failedTaskIds 记录 + code 非 0 表示部分失败 |
| 3 | PROCESS_START 是否返回 activeTaskIds | 返回，免去 Connector 再查一次 |
| 4 | 错误码体系 | 定义错误码枚举（如 `BPM_SESSION_CREATE_FAILED / BPM_IP_NOT_ALLOWED / BPM_PROCESS_NOT_FOUND / BPM_BO_CREATE_FAILED`），统一在 ResponseObject.code 返回 |
| 5 | fieldMap 增强最终范围 | A3：数组批量搬运 + type 转换作为可选配置（不配 type 就原值搬运，向后兼容） |

## 8. 关键代码位置索引

### 老系统（com.awspaas.user.apps.data.bus，仅参考不复用）
- `src/com/awspaas/user/apps/data/bus/processor/impl/BpmCreateSessionProcessor.java` - session_create 旧实现
- `src/com/awspaas/user/apps/data/bus/processor/impl/BoCreateProcessor.java` - bo_create 旧实现
- `src/com/awspaas/user/apps/data/bus/processor/impl/ProcessCreateProcessor.java` - process_start 旧实现
- `src/com/awspaas/user/apps/data/bus/processor/impl/TaskCompleteProcessor.java` - task_complete 旧实现
- `src/com/awspaas/user/apps/data/bus/processor/impl/BaseProcessor.java` - 基类（beforeProcess/shouldSkip/process/afterProcess 链路）
- `src/com/awspaas/user/apps/data/bus/processor/factory/OperationProcessorFactory.java` - 反射工厂（新系统不需要）
- `src/com/awspaas/user/apps/data/bus/util/BOUtil.java` - convertToBOMap（旧数据流，印证上下文原子化）
- `doc/BPM文档.md` - BPM SDK API 入口清单

### 新系统（部分已建，2026-09-18 更新）

**BPM 端总线 app** — 已建（独立仓库 `e:\01.code\com.awspaas.databus.connector`）：
- `src/com/awspaas/databus/connector/controller/DataBusConnectorController.java` - 4 个 @Mapping 端点入口
- `src/com/awspaas/databus/connector/service/BpmBaseService.java` - 公共基类（doExecute 模板 + requireNonBlank/requireNonNull/throwBpmError/buildUserContext）
- `src/com/awspaas/databus/connector/service/SessionCreateService.java` - SESSION_CREATE
- `src/com/awspaas/databus/connector/service/BoCreateService.java` - BO_CREATE
- `src/com/awspaas/databus/connector/service/ProcessStartService.java` - PROCESS_START
- `src/com/awspaas/databus/connector/service/TaskCompleteService.java` - TASK_COMPLETE
- `src/com/awspaas/databus/connector/dto/*Request.java` - 4 个入参 DTO
- `src/com/awspaas/databus/connector/model/BoItem.java` - BO_CREATE 子项
- `src/com/awspaas/databus/connector/exception/{BpmErrorCode.java,BpmConnectorException.java}` - 错误码与异常
- `src/com/awspaas/databus/connector/util/IdCardQueryUtil.java` - idCard 查询（SDK 重写）
- `config/action.xml` - 4 个 @Mapping 注册

**RuoYi-Vue-Plus ruoyi-databus** — 已建 Connector 抽象层与 BPM 子包：
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/Connector.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/Connection.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/ConnectorDescriptor.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/ConnectorException.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/ConnectorRegistry.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmConst.java` - 4 个 cmd 常量
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmHttpConnectionCfg.java`
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmHttpConnector.java` - Connector 实现 + 4 操作方法
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/dto/*` - 4 个 DTO 副本（字段与 BPM 端一致）

**RuoYi-Vue-Plus ruoyi-databus** — 待建 LiteFlow 组件（步骤 3 任务）：
- `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/component/bpm/` - 4 个 BPM 组件 + Cfg（步骤 3）

**plus-ui** - 待建：cmp-defs 物料化 + 连接管理页面（步骤 5-7）

### 已建相关（1C 阶段）
- `ruoyi-databus/src/main/java/org/dromara/databus/component/data/FieldMapComponent.java` - 现有 fieldMap 组件（待增强）
- `ruoyi-databus/src/main/java/org/dromara/databus/component/data/FieldMapCfg.java` - 现有 fieldMap 配置（待增强）

## 9. 相关文档

- [databus-context-design.md](databus-context-design.md) - DatabusContext 执行上下文设计（Connector 层 Context 持有 connectors/connections 双 Map）
- `e:\01.code\databus-meta\.trae\handoff\1d-p0-connector-handoff.md` - 1D-P0 架构交接文档
- `project_memory.md` §Tech Choice Decisions「1D-P0 Connector 架构决策」- 架构决策原文

## 10. 步骤 3 实施前契约参考（2026-09-18 归档）

> 本节归档步骤 3（4 个 LiteFlow BPM 组件）实施前已读源码的契约理解，新窗口无需再读这些源文件，直接按本节契约实施。
> 范围：4 个 BPM 组件（`org.dromara.databus.component.bpm` 包）+ 各自 Cfg 类，共 ~9 个新文件。

### 10.1 BPM 端 4 端点入参/出参契约（已核实，与 BPM 端 Service 实现一致）

#### 10.1.1 SESSION_CREATE
- **入参** `SessionCreateRequest`：
  ```json
  {
    "userName": "xxx",        // 必填
    "password": "xxx",        // 必填
    "clientIp": "0.0.0.0",    // 可选，默认 0.0.0.0
    "lang": "cn",             // 可选，默认 cn
    "device": "PC",           // 可选，默认 PC
    "ipWhiteList": ["..."]    // 可选，由 Connector 从 BpmHttpConnectionCfg 带过来
  }
  ```
- **出参**（BPM 端 ResponseObject.data 字段）：
  ```json
  { "sessionId": "...", "idCard": "..." }
  ```
- BPM 端实现：`SessionCreateService.doCreateSession` — 校验 userName/password 非空 → IdCardQueryUtil 查 idCard（SDK 重写） → IP 白名单校验（ipWhiteList 为空跳过 + warn） → `SDK.getPortalAPI().createClientSessionByDevice(...)` → 解析响应 result=ok + data.sid → 返回 `{sessionId, idCard}`。

#### 10.1.2 BO_CREATE
- **入参** `BoCreateRequest`：
  ```json
  {
    "method": "create",       // 可选，默认 create；可选值 create / createDataBO
    "bindId": "...",          // method=create 时必填
    "uid": "...",             // 必填
    "boList": [               // 必填，至少 1 项
      {
        "boName": "UserBO",   // 必填
        "records": [           // 必填，每个 Map 的 key 已是 BPM 字段名、value 已是正确类型
          { "NAME": "张三", "AGE": 18 }
        ]
      }
    ]
  }
  ```
- **出参**：
  ```json
  {
    "boResults": [
      { "boName": "UserBO", "records": [{...含 ID}], "createdCount": 1 }
    ]
  }
  ```
- BPM 端实现：`BoCreateService.doBoCreate` — 校验 uid/boList 非空 + method 合法 + method=create 时 bindId 非空 → buildUserContext(uid) → 遍历 boList 逐项构造 `List<BO>`（record.forEach bo::set，SDK 不做类型转换） → 按 method 调 `SDK.getBOAPI().create(boName, boList, bindId, uid)` 或 `createDataBO(boName, boList, userContext)` → 收集 `bo.toJSONObject()` 含生成的 ID → 返回 `{boResults: [{boName, records, createdCount}]}`，**不做回写**。
- **6 回写策略归属**：在 Component 层（不在 BPM 端也不在 Connector 协议层）。Component 拿到 boResults 后按 Cfg 中每项 BO 的 `rewrite` 配置执行回写（详见 §10.4）。

#### 10.1.3 PROCESS_START
- **入参** `ProcessStartRequest`：
  ```json
  {
    "processDefId": "xxx",    // 必填
    "uid": "xxx",             // 必填
    "title": "已替换好的纯字符串"  // 必填，Component 调 ctx.resolveMixedPath 替换 ${$.xxx} 后传入
  }
  ```
- **出参**：
  ```json
  {
    "processInstanceId": "...",
    "isProcess": true,
    "activeTaskIds": ["t1", "t2"]
  }
  ```
- BPM 端实现：`ProcessStartService.doProcessStart` — 校验 processDefId/uid/title 非空 → `SDK.getProcessAPI().createBOProcessInstance(...)` → `isProcess=true` 才 `SDK.getProcessAPI().start(pi)` 并收集活跃任务 ID → 非 process 仅创建控制记录不调 start → **不做 autocomplete**（task_complete 独立端点）。

#### 10.1.4 TASK_COMPLETE
- **入参** `TaskCompleteRequest`：
  ```json
  {
    "processInstanceId": "xxx",  // 必填
    "uid": "xxx"                 // 必填
  }
  ```
- **出参**：
  ```json
  {
    "processInstanceId": "...",
    "processEnded": true,
    "completedTaskIds": ["t1"],
    "failedTaskIds": ["t2"],
    "failedErrors": ["失败原因"]
  }
  ```
- BPM 端实现：`TaskCompleteService.doTaskComplete` — 校验 processInstanceId/uid 非空 → `SDK.getProcessQueryAPI().detailById(id)`，null 抛 BPM_PROCESS_INSTANCE_NOT_FOUND → `isEnd()=true` 直接返回"已结束" → buildUserContext → 查活跃任务 → 逐个 completeTask，失败记录到 failedTaskIds + failedErrors（不吞异常）→ 二次 detailById 查 processEnded → **code 始终 0**（决策 9.1.1，部分失败语义由 Connector/Component 翻译）。

### 10.2 ruoyi-databus BpmHttpConnector 4 操作方法签名（已建）

```java
// org.dromara.databus.connector.bpm.BpmHttpConnector
public Object createSession(Connection connection, SessionCreateRequest request);
public Object boCreate   (Connection connection, BoCreateRequest    request);
public Object processStart(Connection connection, ProcessStartRequest request);
public Object taskComplete(Connection connection, TaskCompleteRequest request);
```

返回值都是 BPM 端 ResponseObject.data 字段（Object 类型，Component 层按 Map 取字段）。失败抛 `ConnectorException(code, msg)`。

Connector 取法（Component 层范式）：
```java
import org.dromara.common.core.utils.SpringUtils;
BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
Connection conn = getDatabusContext().getConnection(cfg.getConnectionId());
Object result = connector.createSession(conn, request);
```

`BpmHttpConnectionCfg`（Connector 内部从 Connection.config 反序列化得到）字段：endpoint / authUser / authPassword / timeoutMs / retryCount / ipWhiteList。

ipWhiteList 由 Connector 在 `createSession` 调用前从 cfg 带入 request（testConnection 已示范，正式 Component 调用时也需自己塞入）。但 SESSION_CREATE 端要求 ipWhiteList 时，更稳妥的做法是在 SessionCreateComponent 内调 `BpmHttpConnector.fromConnection(conn)` 取 cfg 后塞入 request（fromConnection 是 public 方法）。

### 10.3 DatabusNodeComponent 基类 API（LiteFlow Component 基类，已建）

```java
// org.dromara.databus.component.DatabusNodeComponent extends NodeComponent
protected DatabusContext getDatabusContext();          // 取上下文（从 LiteFlow slot）
protected <T> T get(String path);                      // 读路径，不存在抛异常
protected <T> T get(String path, TypeRef<T> typeRef);  // 按 TypeRef 读取
protected <T> T getOptional(String path);             // 读路径，不存在返回 null
protected <T> T getOrDefault(String path, T def);     // 读路径，不存在返回默认
protected void save(String path, Object value);        // 写路径，父路径不存在自动建
protected Object resolveParam(Object input);           // 参数解析：纯路径读 / 混合路径替换 / 字面量原样
```

DatabusContext 关键方法：`getConnection(connId)` / `registerConnection(conn)` / `allConnections()` / `resolveMixedPath(template)`（替换 `${$.xxx}`）/ `read(path)` / `readOptional(path)` / `write(path, value)` / `exists(path)` / `toJsonString()`。

LiteFlow 注册范式（参考 HttpRequestComponent）：`@LiteflowComponent("sessionCreate")` 注解注册组件名，组件名要与 cmp-defs 中的 type 一致。

### 10.4 6 回写策略实现要点（来自老系统 BoCreateProcessor，归档供 BoCreateComponent 直接照搬）

Cfg 设计建议（每个 BO 项含 `rewrite` 子配置）：
```yaml
boList:
  - boName: "UserBO"
    sourcePath: "$.request.users"   # 数据空间路径，读取 List<Map> 作为 records
    rewrite:
      strategy: "all"               # no/all/boId/add/exclude/include
      path: "$.response.users"      # 回写目标路径
      addFields: ["ID", "name"]     # add/boId 用
      excludes: ["password"]        # exclude 用
      includes: ["ID", "name"]      # include 用
```

策略语义（与老系统一致）：
- **no**：不回写
- **all**：把 `boResults[i].records`（完整 List<Map>）整体覆写到 `$.<rewrite.path>`
- **boId**：转化为 `add` + `addFields=["ID"]`，对每条记录的 ID 字段写 `$.<path>[j].ID`
- **add**：遍历 boResults[i].records 每条，对 addFields 中每个字段写 `$.<rewrite.path>[j].<field>`（不覆写整体，只补字段）
- **exclude**：从 records 过滤掉 excludes 字段后整体覆写
- **include**：从 records 只保留 includes 字段后整体覆写（includes 为空时退化为 all）

回写用 `save(path, value)`（基类已封装 `getDatabusContext().write`，自动建父路径）。BoCreateComponent 拿到 `boResults` 后按 `boList[i].rewrite.strategy` 分支执行。

### 10.5 4 个 BPM 组件实施清单（步骤 3 任务）

新建包 `org.dromara.databus.component.bpm`，9 个新文件：

| 组件名（@LiteflowComponent） | Cfg 类 | 入参要点 | 出参写入 dataSpace（`$.<tag>.*`） |
|---|---|---|---|
| `sessionCreate` | `SessionCreateCfg` | connectionId / userName / password / lang? / device? / clientIp? | sessionId / idCard |
| `boCreate` | `BoCreateCfg` | connectionId / method? / bindId? / uid / boList:[{boName, sourcePath, rewrite:{strategy,path,addFields,excludes,includes}}] | 按策略回写 + boResults 整体存 `$.<tag>.boResults` |
| `processStart` | `ProcessStartCfg` | connectionId / processDefId / uid / title（含 `${$.xxx}` 模板） | processInstanceId / isProcess / activeTaskIds |
| `taskComplete` | `TaskCompleteCfg` | connectionId / processInstanceId / uid / failOnError?（默认 false） | processInstanceId / processEnded / completedTaskIds / failedTaskIds / failedErrors |

实施要点：
1. Cfg 类放 `org.dromara.databus.component.bpm` 包下，与 Component 同包（参考 HttpRequestCfg/HttpRequestComponent 同包范式）。
2. Cfg 用 Lombok `@Data`，字段名与前端 cmp-defs 物料的 defaults JSON key 一致（驼峰）。
3. Component 继承 `DatabusNodeComponent`，`process()` 内：①`this.getCmpData(XxxCfg.class)` 取 cfg → ②resolveParam 解析 connectionId/method/bindId/uid/processInstanceId 等（支持裸路径/混合字符串/字面量） → ③`SpringUtils.getBean(BpmHttpConnector.class)` 取 Connector → ④`getDatabusContext().getConnection(connId)` 取 Connection → ⑤组装 Request DTO 调对应方法 → ⑥响应按字段写入 `$.<tag>.*`。
4. `processStart` 的 title 字段含 `${$.xxx}` 模板，用 `getDatabusContext().resolveMixedPath(cfg.getTitle())` 替换后传 BPM 端（决策 9.1.6）。
5. `boCreate` 拿到 `boResults`（List<Map>）后：①按 cfg.boList[i].rewrite.strategy 分支回写 → ②整体 boResults 也存一份到 `$.<tag>.boResults` 便于后续组件读 ID。
6. `taskComplete` 看响应 `failedTaskIds` 非空时：cfg.failOnError=true 抛 ServiceException，=false 仅 log.warn 不抛（决策：TASK_COMPLETE 全部尝试 + 记录失败 + 调用方决定是否抛错）。
7. SESSION_CREATE 的 ipWhiteList：调 `connector.fromConnection(conn)` 取 cfg 后塞入 request.setIpWhiteList（避免 Connector 端的 testConnection 是特殊路径，正式 Component 调用时也需自己塞）。
8. 编译验证：`mvnw.cmd -pl ruoyi-modules/ruoyi-databus -am -o compile -Dmaven.test.skip=true`（JAVA_HOME=`C:\Users\Administrator\.jdks\temurin-21`，注意 .jdks 不是 jdks）。**禁止 AI 主动跑 mvnw test**。

### 10.6 步骤 3 后续步骤预告（本节不实施，仅上下文）

- 步骤 4：fieldMap 增强（A3 方案：数组批量搬运 + type 转换可选配置）。改 `FieldMapCfg.Mapping` 加 `type` 字段 + 改 `FieldMapComponent` 加 `[*]` 通配符 + convertType。~2 文件改造。
- 步骤 5：plus-ui cmp-defs 追加 4 BPM 物料 + CmpProps DATA_HINTS 追加 4 条。
- 步骤 6：连接管理后端配套（Controller/Service/Domain/Mapper/xml + 建表 SQL `sys_databus_connection`）。
- 步骤 7：连接管理前端（index.vue / ConnectionForm.vue / ConnectionTestButton.vue + api 2 文件）。
- 全部完成后用户实测完整链路 `sessionCreate → fieldMap → boCreate → processStart → taskComplete` 5 步全过。

## 11. 步骤 3 实施完成归档（2026-09-18）

> 4 个 BPM LiteFlow 组件 + 4 个 Cfg + 1 个 package-info 全部落地，4 轮 `mvnw compile` BUILD SUCCESS。本节沉淀最终契约与设计选择，供后续步骤 4-7 直接对照。

### 11.1 新建文件清单（9 文件，路径 `ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/component/bpm/`）

| 文件 | 角色 |
|---|---|
| `package-info.java` | 包说明，列 4 组件对应 BPM 端点 |
| `SessionCreateCfg.java` | SESSION_CREATE 节点参数（connectionId / userName / password / clientIp? / lang? / device?） |
| `SessionCreateComponent.java` | 注册名 `sessionCreate`，调 `BpmHttpConnector.createSession`，ipWhiteList 由 `connector.fromConnection(conn).getIpWhiteList()` 塞入 |
| `BoCreateCfg.java` | BO_CREATE 节点参数（含嵌套 `BoItemCfg` + `RewriteCfg`） |
| `BoCreateComponent.java` | 注册名 `boCreate`，调 `BpmHttpConnector.boCreate`，实现 6 回写策略 + 整体 boResults 存 `$.<tag>.boResults` |
| `ProcessStartCfg.java` | PROCESS_START 节点参数（connectionId / processDefId / uid / title） |
| `ProcessStartComponent.java` | 注册名 `processStart`，调 `BpmHttpConnector.processStart`，title 经 `getDatabusContext().resolveMixedPath(...)` 替换 |
| `TaskCompleteCfg.java` | TASK_COMPLETE 节点参数（connectionId / processInstanceId / uid / failOnError?） |
| `TaskCompleteComponent.java` | 注册名 `taskComplete`，调 `BpmHttpConnector.taskComplete`，部分失败按 `failOnError` 翻译 |

### 11.2 实施过程设计选择（与 §10.5 实施清单的对照）

1. **Cfg 字段命名**：与 §10.5 表格一致（驼峰），字段都为可空包装类型（String/Boolean），默认值在 Component 层给（避免 Cfg 默认值与 Component 默认值双轨不一致）。
2. **Component 流程统一模板**：取 Cfg → 校验必填 → 取 tag → 解析参数（`resolveStr` 内调基类 `resolveParam`）→ `SpringUtils.getBean(BpmHttpConnector.class)` 取 Connector → `getDatabusContext().getConnection(connectionId)` 取 Connection → 组装 Request → 调 Connector → 响应按 `resultMap.forEach((key, value) -> save("$." + tag + "." + key, value))` 平铺到 `$.<tag>.*`。
3. **参数解析范式**：`resolveStr` 内调基类 `resolveParam(input)`（基类调 `getDatabusContext().resolve(input)`，对纯 JSONPath 读值、对混合字符串替换 `${$.xxx}`、对字面量原样返回），最后 `toString()`。这覆盖 connectionId / userName / password / method / bindId / uid / processDefId / processInstanceId 等所有字符串入参。
4. **BoCreate sourcePath**：当纯 JSONPath 处理（用基类 `get(path, TypeRef)` 读 `List<Map<String, Object>>`），不支持字面量。语义比 mixed path 更清晰：sourcePath 本质就是路径。
5. **BoCreate 6 回写策略实现**（与 §10.4 一致）：
   - `no`：跳过
   - `all`：`save(path, records)` 整体覆写
   - `boId`：转 `add + addFields=List.of("ID")`，逐条逐字段写 `$.<path>[j].<field>`，靠基类 `save` 的 `handleArrayPath` 自动扩容
   - `add`：同上，按 cfg.addFields
   - `exclude`：`filterRecords(records, excludes, keepFlag=false)` 过滤后整体覆写
   - `include`：`filterRecords(records, includes, keepFlag=true)` 只保留后整体覆写；includes 为空退化为 `all`
6. **BoCreate boResults 双写**：①整体存 `$.<tag>.boResults`（供后续组件读 ID）②按 `cfg.boList[i].rewrite` 顺序对应 `boResults[i]` 执行回写；数量不一致抛异常。
7. **ProcessStart title 模板替换**：`getDatabusContext().resolveMixedPath(cfg.getTitle())`——专门替换 `${$.xxx}` 片段，不解析纯路径（避免 title 是 `$.user.name` 时被当成路径读出对象转成字符串 "null"）。与基类 `resolveParam` 的差别：resolveParam 对纯路径会 read，resolveMixedPath 只做模板替换。title 用 resolveMixedPath 是 §10.5 实施要点 4 的明确要求。
8. **TaskComplete 部分失败语义**：响应 `failedTaskIds` 非空（且 `instanceof List` 非空）时按 `cfg.failOnError` 翻译：true 抛 ServiceException 中断，false 仅 log.warn。`completedTaskIds` / `failedErrors` 始终写入 `$.<tag>.*`，调用方可在后续节点读取决策。这覆盖 §10.5 实施要点 6 与决策 9.1.3。
9. **SessionCreate ipWhiteList 塞入**：调 `connector.fromConnection(conn).getIpWhiteList()`（public 方法，§10.2 已说明），为 null 时塞 `List.of()`。这覆盖 §10.5 实施要点 7。
10. **响应平铺策略统一**：4 个组件响应都是 BPM 端 ResponseObject.data（一个 Map），统一用 `resultMap.forEach((key, value) -> save("$." + tag + "." + key, value))` 平铺；boCreate 额外双写 boResults（已含在 Map 内，forEach 已自动写入，单独 log 强调）。好处：BPM 端响应字段增减时 Component 层零改动。

### 11.3 4 轮编译记录（mvnw BUILD SUCCESS）

| 轮次 | 累计文件 | 耗时 | 状态 |
|---|---|---|---|
| 1 (package-info + SessionCreateCfg + SessionCreateComponent) | 89 | 4.118s | SUCCESS |
| 2 (+ BoCreateCfg + BoCreateComponent) | 91 | 4.153s | SUCCESS |
| 3 (+ ProcessStartCfg + ProcessStartComponent) | 93 | 4.073s | SUCCESS |
| 4 (+ TaskCompleteCfg + TaskCompleteComponent) | 95 | 4.035s | SUCCESS |

每轮编译都触发 ruoyi-databus 整模块重新编译（source code changed detection），无 incremental skip。无错误无警告（仅有 AbstractExpressParser 既有 deprecation 提示，与本次改动无关）。

### 11.4 步骤 3 完成后的状态边界

- ✅ 4 BPM 组件代码全部落地，编译通过
- ❌ **未实测**：用户尚未重启后端、未配置 Connection、未跑 EL 试运行（按项目约束：禁止 AI 主动跑 mvnw test / 操作浏览器）
- ❌ **未配套前端 cmp-defs 物料**：步骤 5 才追加 4 个 BPM 物料到 plus-ui，本步骤后无法在画布上拖出 4 个组件
- ❌ **未配套连接管理**：步骤 6-7 才建 sys_databus_connection 表 + 前端管理页，本步骤后无法在 UI 上配 BPM 连接
- ⚠ **试运行可用但需手配**：理论上可以手写 EL `THEN(sessionCreate.tag("s1").data("{...}"))` 跑试运行，但需要用户在试运行入参弹窗里手填 connectionId 对应的 Connection 配置（注入 DatabusContext.connections 注册表）。建议等步骤 5-7 完成后再做完整链路实测

### 11.5 步骤 4 启动条件与实施预告

**步骤 4 启动条件**：用户确认步骤 3 已落地，可继续步骤 4。

**步骤 4 任务**：fieldMap A3 增强（[§10.6 步骤 4](#106-步骤-3-后续步骤预告本节不实施仅上下文)）
- 改 `FieldMapCfg.Mapping` 加 `type` 字段（`int/string/boolean/double`，可空，空则原值搬运）
- 改 `FieldMapComponent.process()` 加：
  - `[*]` 通配符路径读取（如 `$.orders[*].NAME` → `List<Object>`，对每个元素作为单值处理）
  - `convertType(value, type)` 类型转换（Integer.parseInt / String.valueOf / Boolean.parseBoolean / Double.parseDouble），失败抛 ServiceException
- 兼容现有：不配 type 时行为完全一致（原值搬运），向后兼容
- 预计 ~2 文件改造，无新建文件

## 12. 步骤 4 实施完成归档（2026-09-18）

> fieldMap A3 增强落地，2 文件改造，1 轮 `mvnw compile` BUILD SUCCESS。

### 12.1 改动文件清单（2 文件）

| 文件 | 改动 |
|---|---|
| `FieldMapCfg.java` | `Mapping` 加 `type` 字段（`int/string/boolean/double`，可空）+ 类注释更新（含两种搬运语义 + type 可选说明） |
| `FieldMapComponent.java` | `process()` 加 `[*]` 通配符读取分支（4 种 from/to 组合） + `convertType(value, type)` 方法 + `batchMap(...)` 私有方法 |

### 12.2 设计选择

#### 12.2.1 from/to 通配符组合的 4 分支

| from 含 `[*]` | to 含 `[*]` | 分支 | 行为 |
|---|---|---|---|
| ✅ | ✅ | a 数组批量搬运（核心新能力） | `exists(from)` 判断存在；存在则 `get(from, TypeRef<List<Object>>)` 读全部元素，逐元素 `convertType`（可选）+ `to.replace("[*]", "[" + i + "]")` 按索引写目标位置；整条 mapping 算 1 条。源路径不存在跳过（count 不增） |
| ✅ | ❌ | b | 抛 `ServiceException("字段映射条目 from 含 [*] 但 to 不含 [*]，无法批量写入")` |
| ❌ | ✅ | c | 抛 `ServiceException("字段映射条目 to 含 [*] 但 from 不含 [*]，无法批量写入")` |
| ❌ | ❌ | d 单值搬运（保持现状，可选 type 转换） | `getOptional(from)` 读单值；type 非空且 value != null 时 `convertType`；`save(to, value)` |

#### 12.2.2 convertType 实现

- `value == null` 时直接抛 `ServiceException("fieldMap type 转换失败：value 为 null type=" + type)`（不允许把 null 强转）
- `switch (type)`：
  - `int` → `Integer.parseInt(value.toString())`（NumberFormatException 包装）
  - `string` → `String.valueOf(value)`
  - `boolean` → `Boolean.parseBoolean(value.toString())`
  - `double` → `Double.parseDouble(value.toString())`（NumberFormatException 包装）
  - default → `ServiceException("fieldMap 不支持的 type: " + type + "（支持 int/string/boolean/double）")`
- 任何 NumberFormatException / 其他异常包装为 `ServiceException("fieldMap type 转换失败 type=" + type + " value=" + value, e)`
- `ServiceException` 自身（含 default 分支）原样抛出不再包装

#### 12.2.3 向后兼容说明

- **不配 type 且 from/to 不含 `[*]`**：行为完全等同改造前——`getOptional(from)` 读单值原样 `save(to, value)`，count++，无 type 转换、无路径分支变化。
- Cfg 用 Lombok `@Data`，新增 `type` 字段不配时默认 `null`，反序列化既有 JSON 不受影响。
- 组件注册名 `fieldMap` 保持不变，调用方零改动。

#### 12.2.4 批量分支细节

- `[*]` 语义（已核实，避免臆测）：`$.orders[*].NAME` 返回所有 orders 元素的 NAME 字段值按顺序组成的 `List<Object>`；用 `document.read(path, TypeRef<List<Object>>)` 读取。
- 路径不存在时抛 `PathNotFoundException`——故 batchMap 先 `getDatabusContext().exists(from)` 兜底判断，不存在跳过（避免抛异常中断流程）。
- `to` 含 `[*]` 时按索引替换：`$.target.items[*].name` → `$.target.items[0].name` / `$.target.items[1].name` ...，靠基类 `save` 的 `handleArrayPath` 自动扩容目标数组。

### 12.3 编译记录

| 轮次 | 累计文件 | 耗时 | 状态 |
|---|---|---|---|
| 1（FieldMapCfg + FieldMapComponent） | 97 | 3.933s | SUCCESS |

ruoyi-databus 整模块重新编译，无错误无警告（仅有 AbstractExpressParser 既有 deprecation 提示，与本次改动无关）。

### 12.4 状态边界

- ✅ fieldMap A3 代码落地，`mvnw compile` BUILD SUCCESS
- ❌ **未实测**（按项目约束：禁止 AI 主动跑 `mvnw test` / 操作浏览器）
- ❌ **未配套前端 cmp-defs**（步骤 5 才追加 fieldMap 的 `type` 字段提示 + `[*]` 通配符提示到 plus-ui cmp-defs）
- ⚠ **批量分支未跑 EL 试运行**：理论上可手写 EL `THEN(fieldMap.tag("f1").data("{mappings:[{from:\"$.orders[*].NAME\",to:\"$.target.items[*].name\",type:\"string\"}]}"))` 验证，但建议等步骤 5-7 配套完成后再做完整链路实测

### 12.5 步骤 5 启动条件与实施预告

**步骤 5 启动条件**：用户确认步骤 4 已落地，可继续步骤 5。

**步骤 5 任务**：plus-ui cmp-defs 追加 4 BPM 物料 + CmpProps DATA_HINTS 追加 4 条 + fieldMap 物料加 `type` 字段提示

- cmp-defs 追加 4 个 BPM 物料：`sessionCreate` / `boCreate` / `processStart` / `taskComplete`（与 ruoyi-databus 端 4 个 `@LiteflowComponent` 注册名一一对应）
- `CmpProps` DATA_HINTS 追加 4 条 BPM 物料的字段提示
- `fieldMap` 物料加 `type` 字段提示（可选值 `int/string/boolean/double`）+ `[*]` 通配符提示（from/to 同时含 `[*]` 触发批量搬运）

等用户说"动手"再开工。

## 13. 步骤 5 实施完成归档（2026-09-18）

> plus-ui cmp-defs 追加 4 BPM 物料 + CmpProps DATA_HINTS 追加 4 条 + fieldMap 物料加 type/[*] 提示，2 文件改造，oxlint + vue-tsc 全过。

### 13.1 改动文件清单（2 文件）

| 文件 | 改动 |
|---|---|
| `cmp-defs.ts` | `CMP_DEFS` 末尾追加 4 BPM 物料（sessionCreate / boCreate / processStart / taskComplete，注册名与 ruoyi-databus 端 `@LiteflowComponent` 一一对应）；`fieldMap` 物料 `desc` 追加 `[*]` 通配符说明 + `type` 字段可选说明 |
| `CmpProps.vue` | `DATA_HINTS` 追加 4 BPM 物料配置示例 + `fieldMap` hint 更新为含 type + `[*]` 双 mapping 示例（单值带 type + 批量带 type） |

### 13.2 4 BPM 物料定义

| type | label | short | icon | color | desc |
|---|---|---|---|---|---|
| `sessionCreate` | BPM 会话 | 会话 | `ph:sign-in` | #409eff | 创建 BPM 会话（登录获取 sid），响应平铺到 $.数据空间 |
| `boCreate` | BPM 建 BO | 建 BO | `ph:database` | #9c27b0 | 创建 BPM 业务对象（BO），支持 6 种回写策略，结果存 $.数据空间.boResults |
| `processStart` | BPM 启流程 | 启流程 | `ph:rocket` | #e6a23c | 启动 BPM 流程实例，title 支持 ${$.xxx} 模板替换 |
| `taskComplete` | BPM 完任务 | 完任务 | `ph:seal-check` | #67c23a | 按 processInstanceId 提交 BPM 任务（全部尝试），部分失败按 failOnError 决定是否中断 |

4 物料均 `group: 'business'`，无 `operator`/`virtual`/`singleton`/`lfNodeType`（普通业务组件），画布上可拖多次，由 `useElTreeModel` 自动加同类型序号区分 tag。

物料在 `CMP_DEFS` 数组中的追加位置在 `response` 物料之后（即业务组件区块末尾），顺序按 BPM 主线编排自然递进：会话 → 建 BO → 启流程 → 完任务。

### 13.3 DATA_HINTS 配置示例

| 物料 | hint |
|---|---|
| `sessionCreate` | `{"connectionId":"bpm-default","userName":"admin","password":"$.request.password"}` |
| `boCreate` | `{"connectionId":"bpm-default","method":"create","bindId":"bo-001","uid":"admin","boList":[{"boName":"UserBO","sourcePath":"$.request.users","rewrite":{"strategy":"all","path":"$.response.users"}}]}` |
| `processStart` | `{"connectionId":"bpm-default","processDefId":"proc-001","uid":"admin","title":"申请-${$.request.code}"}` |
| `taskComplete` | `{"connectionId":"bpm-default","processInstanceId":"$.processStart1.processInstanceId","uid":"admin","failOnError":false}` |
| `fieldMap`（更新前） | `{"mappings":[{"from":"$.httpRequest1.response.msg","to":"$.fieldMap1.msg"}]}` |
| `fieldMap`（更新后） | `{"mappings":[{"from":"$.httpRequest1.response.code","to":"$.fieldMap1.code","type":"int"},{"from":"$.httpRequest1.response.data[*].NAME","to":"$.fieldMap1.items[*].name","type":"string"}]}` |

`fieldMap` 新 hint 同时演示单值带 type 与批量搬运带 type 两种用法，与 §12.2 步骤 4 实施的 4 分支保持一致。

### 13.4 图标核实记录（按项目硬约束：禁止臆测）

经 `api.iconify.design/ph.json?icons=...` 核实存在的 Phosphor 图标：
- `ph:sign-in` ✅
- `ph:database` ✅
- `ph:rocket` ✅
- `ph:seal-check` ✅

不存在的候选：`ph:database-plus`（not_found）、`ph:clipboard-check`（not_found）——已弃用，改用 `ph:database` / `ph:seal-check`。

### 13.5 静态验证记录

| 工具 | 命令 | 结果 |
|---|---|---|
| oxlint | `npx oxlint cmp-defs.ts CmpProps.vue` | 0 warnings, 0 errors（2 文件，156 rules） |
| vue-tsc | `npx vue-tsc --noEmit -p tsconfig.json` | databus/editor 0 errors；仅剩 monitor/logininfo 基线大小写问题（与本次改动无关，2026-09-17 既有） |

### 13.6 状态边界

- ✅ plus-ui cmp-defs 4 BPM 物料 + DATA_HINTS 4 条 + fieldMap type/[*] 提示全部落地，静态校验全过
- ❌ **未实测**（按项目约束：禁止 AI 主动跑 vitest / 操作浏览器）
- ❌ **未配套连接管理后端**（步骤 6 才建 `sys_databus_connection` 表 + Controller/Service/Domain/Mapper/xml）
- ❌ **未配套连接管理前端**（步骤 7 才建 index.vue / ConnectionForm.vue / ConnectionTestButton.vue + api 2 文件）
- ⚠ **画布可用但 BPM 物料需手配 Connection**：本步骤后可在画布拖出 4 个 BPM 物料并填配置 JSON，但试运行时 Connection 注册表为空（DatabusContext.connections 未注入），需要用户在试运行入参里手填 Connection 配置注入。建议等步骤 6-7 完成后再做完整链路实测。

### 13.7 步骤 6 启动条件与实施预告

**步骤 6 启动条件**：用户确认步骤 5 已落地，可继续步骤 6。

**步骤 6 任务**：连接管理后端配套

- 建表 SQL：`sys_databus_connection`（id / connection_id / connection_name / connector_type / endpoint / username / password / ip_white_list / timeout / retry_count / enabled / create_time / update_time / create_by / update_by / deleted）
- 后端 5 件套：Domain（实体）+ Mapper（+ xml）+ Service（+ impl）+ Controller + bo/vo（请求/响应）
- Connector 类型注册表对接：Service 层调 `DatabusContext.registerConnection(connId, new Connection(...))` 把 DB 配置注入运行时上下文
- 端点设计：CRUD（list / detail / add / edit / remove / testConnection）
- `testConnection` 端点调 `BpmHttpConnector.testConnection(...)` 实测连通性

等用户说"动手"再开工。

## 14. 步骤 6 实施归档（连接管理后端配套，2026-09-18 落地）

### 14.1 落地清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `script/sql/databus_connection.sql` | SQL | 建表 + 菜单/权限（菜单 ID 段预留注释，待用户确认父菜单 ID 启用） |
| `domain/SysDatabusConnection.java` | Domain | extends BaseEntity，@TableLogic 逻辑删除，业务唯一键 connection_id 与主键 id 分离 |
| `domain/bo/SysDatabusConnectionBo.java` | BO | @AutoMapper + AddGroup/EditGroup 验证组 |
| `domain/vo/SysDatabusConnectionVo.java` | VO | @AutoMapper，含 create/updateTime |
| `mapper/SysDatabusConnectionMapper.java` | Mapper | extends BaseMapperPlus，无需 xml |
| `service/ISysDatabusConnectionService.java` | 接口 | 7 方法（CRUD 5 + testConnection + loadEnabledConnections） |
| `service/impl/SysDatabusConnectionServiceImpl.java` | 实现 | @Service + @RequiredArgsConstructor 注入 mapper + ConnectorRegistry |
| `controller/SysDatabusConnectionController.java` | Controller | 6 端点（CRUD 5 + /test） |
| `executor/DatabusExecutor.java` | 改造 | 注入 ISysDatabusConnectionService，三入口调 injectConnections |

### 14.2 端点表

| HTTP | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/databus/connection/list` | `databus:connection:list` | 分页查询（支持 connectionId/connectionName/connectorType/enabled 过滤） |
| GET | `/databus/connection/{id}` | `databus:connection:query` | 详情 |
| POST | `/databus/connection` | `databus:connection:add` | 新增（校验 connectionId 唯一 + connectorType 已注册） |
| PUT | `/databus/connection` | `databus:connection:edit` | 修改（同上校验） |
| DELETE | `/databus/connection/{ids}` | `databus:connection:remove` | 批量逻辑删除 |
| POST | `/databus/connection/test` | `databus:connection:test` | 测试连接（无需落库，前端"测试连接"按钮直传当前表单值） |

### 14.3 DB 字段 ↔ Connection.config Map 字段映射

| DB 字段（sys_databus_connection） | Connection.config Map key | BpmHttpConnectionCfg POJO 字段 | 说明 |
|---|---|---|---|
| `connection_id` | `Connection.id`（顶层） | — | 业务唯一键，组件层引用此值 |
| `connection_name` | `Connection.name`（顶层） | — | 用户可读 |
| `connector_type` | `Connection.connectorType`（顶层） | — | 关联 ConnectorRegistry.ofType |
| `endpoint` | `config.endpoint` | `endpoint` | BPM 容器地址 |
| `username` | `config.authUser` | `authUser` | 默认 BPM 用户（连接级默认 uid） |
| `password` | `config.authPassword` | `authPassword` | 默认密码 |
| `timeout` | `config.timeoutMs` | `timeoutMs` | HTTP 超时毫秒，空时默认 30000 |
| `retry_count` | `config.retryCount` | `retryCount` | 失败重试，空时默认 0（1D-P0 未实现） |
| `ip_white_list` | `config.ipWhiteList`（List<String>） | `ipWhiteList` | DB 存 JSON 字符串，运行时解析为 List |
| `enabled` | — | — | Y 启用 / N 禁用；启用时执行链路自动注入 DatabusContext |

字段映射规则集中在 `SysDatabusConnectionServiceImpl.toConnection(entity)` + `buildConfigMap(entity)`，单点维护，后续扩展 Connector 类型只需扩展此方法。

### 14.4 Service 方法清单

| 方法 | 用途 | 调用方 |
|---|---|---|
| `queryById(Long)` | 详情查询 | Controller.getInfo |
| `queryPageList(bo, pageQuery)` | 分页列表 | Controller.list |
| `insertByBo(bo)` | 新增（校验 connectionId 唯一 + connectorType 已注册） | Controller.add |
| `updateByBo(bo)` | 修改（同上校验） | Controller.edit |
| `deleteByIds(ids)` | 批量逻辑删除 | Controller.remove |
| `testConnection(bo)` | 测试连接（无需落库，BO→Connection→ConnectorRegistry.ofType→testConnection） | Controller.test |
| `loadEnabledConnections()` | 加载启用的 DB 记录转运行时 Connection 列表 | DatabusExecutor.injectConnections |

### 14.5 DatabusExecutor 对接点（关键）

原 `DatabusExecutor.execute/executeByEl/executeRaw` 三入口创建 `DatabusContext` 后未注入连接，导致组件层 `ctx.getConnection(connId)` 永远抛"未注册"。本步骤在三入口都加 `injectConnections(context)`：

```java
DatabusContext context = DatabusContext.fromObject(requestData);
injectConnections(context);  // 新增：从 DB 加载 enabled=Y 的连接注入 context

private void injectConnections(DatabusContext context) {
    List<Connection> connections = connectionService.loadEnabledConnections();
    if (connections == null || connections.isEmpty()) {
        log.debug("[databus] 无启用的连接，跳过注入");
        return;
    }
    for (Connection conn : connections) {
        context.registerConnection(conn);
    }
    log.info("[databus] 注入连接 {} 个: {}", connections.size(),
        connections.stream().map(Connection::getId).toList());
}
```

性能取舍：每次执行链路都查一次 DB（不加缓存），确保连接管理页修改后立即可见；后续如有性能压力可加缓存。

容错：`loadEnabledConnections` 单条转换失败不阻断执行（已记录 ERROR 日志），后续组件取该 connectionId 时会抛"未注册"——这是合理的失败传播路径。

### 14.6 状态边界

- ✅ **后端 6 端点已就绪**：CRUD + testConnection，沿用 RuoYi-Vue-Plus CRUD 范式（@SaCheckPermission + @Log + @RepeatSubmit + MapstructUtils + LambdaQueryWrapper + BaseMapperPlus）
- ✅ **DatabusContext 连接注入路径已打通**：DB → Service.loadEnabledConnections → DatabusExecutor.injectConnections → DatabusContext.registerConnection → 组件层 ctx.getConnection
- ✅ **mvnw compile BUILD SUCCESS**（102 文件，11.928s）
- ⚠ **用户前置操作**：①在 DB 执行 `script/sql/databus_connection.sql` 建表 ②在 sys_menu 配置连接管理菜单 + 6 权限（databus:connection:list/query/add/edit/remove/test）③重启后端验证 6 端点可访问
- ⚠ **菜单 SQL 已预留但注释**：`script/sql/databus_connection.sql` 末尾菜单 insert 语句默认注释，因父菜单 ID 需用户确认（既有 databus editor 菜单 ID 段为 1762000000000000xxx，若冲突需调整），用户可在 SQL 里取消注释 + 替换父菜单 ID 后执行
- ❌ **前端管理页未配套**（步骤 7 才建 plus-ui/src/views/databus/connection/index.vue + ConnectionForm + ConnectionTestButton + api 2 文件）
- ❌ **未实测**：按项目约束（禁止 AI 主动跑 mvnw test / 操作浏览器），需用户在步骤 7 前端配套完成后实测完整链路（sessionCreate → fieldMap → boCreate → processStart → taskComplete 5 步全过）

### 14.7 步骤 7 启动预告

- 前端 3 组件：`plus-ui/src/views/databus/connection/index.vue`（列表页 + 搜索 + 分页 + 删除按钮）+ `ConnectionForm.vue`（新增/编辑弹窗 + 字段平铺表单，含 connectionId/endpoint/username/password/ipWhiteList/timeout/retryCount/enabled/remark）+ `ConnectionTestButton.vue`（独立测试按钮，调用 /databus/connection/test 端点，失败展示 msg）
- api 2 文件：`api/databus/connection/types.ts`（SysDatabusConnectionVo / Bo 类型）+ `api/databus/connection/index.ts`（listConnection/getConnection/addConnection/updateConnection/delConnection/testConnection 6 方法）
- 字段渲染：endpoint/username/password 走 input，password 用 type=password，ipWhiteList 用 textarea（JSON 数组字符串，前端可考虑做成 tag 输入但 1D-P0 先用 textarea），timeout/retryCount 用 number input，enabled 用 switch（Y/N），remark 用 textarea
- 验证：oxlint + vue-tsc 全过；用户在浏览器实测完整链路

等用户说"动手"再开工。

## 15. 步骤 7 实施归档（连接管理前端配套，2026-09-18 落地）

> ⚠ 本节记录的"后端平铺列 + 前端平铺表单"模型在落地当天即被 §16 决策推翻：存储将改为通用列 + config JSON + credentials 加密列；前端本阶段不变。读本节时以 §16 为最新设计。

### 15.1 落地清单（plus-ui，5 新建 + 1 SQL 修正，零前端路由代码改动）

| 文件 | 性质 | 说明 |
|---|---|---|
| `src/api/databus/connection/types.ts` | 新建 | `SysDatabusConnectionVo`（14 字段对齐后端 VO）/ `SysDatabusConnectionBo`（= Omit createTime/updateTime）/ `SysDatabusConnectionQuery extends PageQuery`（4 过滤字段） |
| `src/api/databus/connection/index.ts` | 新建 | 6 方法 listConnection/getConnection/addConnection/updateConnection/delConnection/testConnection + 导出 `CONNECTOR_OPTIONS`（连接器类型选项单一事实源，表单 select 与列表标签共用） |
| `src/views/databus/connection/index.vue` | 新建 | 列表页（name=DatabusConnection）：搜索 4 项 + 分页 + 勾选批量删除 + 行内修改/删除 + 启用状态 Tag，沿用 notice/config 页 page-shell 外壳（search-panel/table-panel/toolbar-shell） |
| `src/views/databus/connection/ConnectionForm.vue` | 新建 | 新增/编辑弹窗：`defineExpose({ openDialog })`，无参新增、传 id 编辑（内部 GET /{id} 重拉详情回显）；提交成功 emit('success') 父刷新；footer 左侧内嵌测试按钮、右侧取消/确定 |
| `src/views/databus/connection/ConnectionTestButton.vue` | 新建 | 独立测试按钮（databus:connection:test 权限）：props 收当前表单值，POST /test 直传不落库 |
| `script/sql/databus_connection.sql`（RuoYi-Vue-Plus） | 修正 | 末尾菜单模板：原模板列数/权限标识不准（旧 16 值缺 perms），重写为 sys_menu 22 列准确版本（6 行：1 菜单 C + 5 按钮 F），保持注释，补齐 databus:connection:* 权限标识，图标改用已核实存在的 link.svg |

### 15.2 后端端点 ↔ 前端 API 对照

| 后端端点 | 权限 | 前端方法 |
|---|---|---|
| GET `/databus/connection/list` | databus:connection:list | `listConnection(query)` → `res.data.rows/total` |
| GET `/databus/connection/{id}` | databus:connection:query | `getConnection(id)`（编辑回显） |
| POST `/databus/connection` | databus:connection:add | `addConnection(data)` |
| PUT `/databus/connection` | databus:connection:edit | `updateConnection(data)` |
| DELETE `/databus/connection/{ids}` | databus:connection:remove | `delConnection(id \| id[])`（单条/批量共用） |
| POST `/databus/connection/test` | databus:connection:test | `testConnection(data)` |

### 15.3 字段契约 ↔ 表单控件对照（与后端 BO/VO 一一对应）

| 字段 | 必填/长度（后端） | 控件 | 说明 |
|---|---|---|---|
| connectionId | 必填 ≤64，全局唯一 | el-input | 组件层引用的业务键；后端唯一性冲突返回"连接ID 'x' 已存在" |
| connectionName | 必填 ≤100 | el-input | — |
| connectorType | 必填 ≤32 | el-select | 1D-P0 单选项 bpmHttp（新增默认选中），选项来自 API 层 CONNECTOR_OPTIONS；保存时后端校验类型已在 ConnectorRegistry 注册 |
| endpoint | ≤255 | el-input | placeholder 给 http://localhost:8088 范式 |
| username | ≤64 | el-input | BPM 登录名；testConnection 后端强制非空，按钮前置拦一道 |
| password | ≤128 | el-input type=password + show-password | — |
| ipWhiteList | ≤1024 | el-input textarea(2 行) | JSON 数组字符串；前端自定义 validator（非空时必须合法 JSON 且为数组），与后端 parseIpWhiteList 口径一致；空串=不限制 |
| timeout | 后端兜底 30000 | el-input-number（1000~600000，step 1000） | 毫秒 |
| retryCount | 后端兜底 0 | el-input-number（0~10） | 1D-P0 保留字段，控件上标注 |
| enabled | 后端兜底 Y | el-switch（active Y / inactive N） | 列表页 Tag 展示，Y 才会在执行链路注入 DatabusContext |
| remark | ≤500 | el-input textarea(2 行) | — |
| id / createTime / updateTime | 只读 | 不编辑 | id 区分新增/编辑提交；列表展示 updateTime（后端按 updateTime desc 排序） |

### 15.4 关键实现决策

1. **不改前端路由代码**：plus-ui 走后端动态路由（`permission.ts` 用 `import.meta.glob('views/**/*.vue')` 按 sys_menu.component 字符串映射），菜单 component 填 `databus/connection/index` 即可，无需注册静态路由。
2. **CONNECTOR_OPTIONS 放 API 模块**：`<script setup>` 不允许 export 常量，类型文件不放运行时值，故选项常量放 `api/databus/connection/index.ts` 作为单一事实源，表单与列表标签映射共用，后续接入新 Connector 只改一处。
3. **测试失败提示归 request 拦截器**：后端 test 失败抛 ServiceException → R.fail（code 500），响应拦截器统一 ElMessage.error(msg) 并 reject；按钮 catch 静默，避免重复弹窗。成功取 R.data（"BPM 连接测试成功: ..."含 BPM 实际响应）展示。
4. **测试按钮前置轻校验**：connectorType/endpoint/username 非空 + ipWhiteList JSON 格式；必填完整性仍归表单 rules（职责不重复——按钮只拦"发出去必败"的请求）。
5. **编辑回显重拉详情**：openDialog(id) 内部 GET /{id}，跟随 notice/config 页范式，不依赖列表行字段；详情拉取失败（拦截器已弹错）自动关弹窗。
6. **删除单条/批量共用一个 handleDelete**：有 row 用 row.id 且确认文案带连接名称，无 row 用勾选 ids；后端 DELETE 本身支持 ids 数组。
7. **默认值前端先给、后端兜底双保险**：新增表单预填 timeout=30000/retryCount=0/enabled=Y/connectorType=bpmHttp，与后端 normalizeDefaults 一致，即使绕过页面直接调 API 也不会落 null。
8. **模板单根合规**：3 个 vue 文件根级均单元素（el-button / el-dialog / div），说明性注释全部在 script 内或文件 SQL 注释中。

### 15.5 静态验证记录（2026-09-18）

- `npx oxlint src/views/databus/connection src/api/databus/connection`：**0 warnings / 0 errors**（5 files，156 rules）
- `npx vue-tsc --noEmit`：databus 目录 **0 errors**；全量仅剩 `monitor/logininfo` 2 个 TS1149 大小写基线错误（2026-09-17 既有，与本次无关）
- 过程中修复 2 个类型错误：el-table 插槽 row（DefaultRow）不能直接赋必填 VO——openDialog 参数收窄为 `id?: number | string`，页面 handleUpdate/handleDelete 参数改 `Partial<SysDatabusConnectionVo>`（notice 页同款写法）
- IDE 诊断（GetDiagnostics）：空

### 15.6 用户实测前置与验证点

前置：①DB 已执行建表（步骤 6）②sys_menu 配置 1 菜单 + 5 按钮权限（推荐菜单管理 UI 添加；或把 `databus_connection.sql` 末尾注释模板替换真实父菜单 ID 后执行）③给当前角色勾选 databus:connection:* 权限 ④后端已重启。

建议验证点：

1. 菜单进入"数据总线 → 连接管理"，列表空态/分页正常
2. 新增：只填 connectionId/连接名称，其余默认（connectorType 自动 bpmHttp、30000/0/Y）→ 保存成功且列表出现
3. connectionId 重复新增 → 后端报"连接ID 'x' 已存在"
4. IP 白名单填非法 JSON（如 `abc`）→ 表单 validator 拦截；填 `["1.2.3.4"]` 合法
5. 弹窗内点"测试连接"：endpoint/username 缺失时前置 warning；填对 BPM 地址后成功提示含"BPM 连接测试成功"；BPM 未启动时错误提示由拦截器弹出且不重复
6. 编辑：回显字段完整，改名称/禁用后保存生效；禁用后该连接不注入 DatabusContext（链路组件取 connectionId 报未注册）
7. 单条删除 + 勾选批量删除，确认文案分别带名称/ID
8. 搜索：connectionId/名称模糊、类型/状态精确；重置恢复
9. 最终全链路：sessionCreate → fieldMap → boCreate → processStart → taskComplete 5 步实测（连接 enabled=Y 是前提）

## 16. 连接存储模型修正决策（2026-09-18 讨论拍板，§15 平铺模型据此返工）

> 状态：**已拍板并落地（2026-09-18 返工完成，mvnw compile BUILD SUCCESS）**。步骤 7 当天落地的平铺模型已按本节重构；前端零改动。待用户重建表 + 重启实测。

### 16.1 背景与问题

步骤 6/7 把 BPM 连接参数（endpoint/username/password/ip_white_list/timeout/retry_count）平铺成 `sys_databus_connection` 的列。未来每接入一种 connector（JDBC/MQ/HTTP…），沿用平铺就得给同一张表加列——形成大量 NULL 的稀疏"万能表"，且新增 connector 要 DDL 改表，与"Connector 物料化为 jar、物料市场热插拔"目标冲突。运行时模型（`Connection.connectorType + config Map`）本就是类型 + 配置 Map 形态，平铺只是存储层的人工转换（`buildConfigMap`）。

业界参照：n8n 凭据表（加密 JSON）、Kafka Connect（configs 本质 `Map<String,String>`）、Airbyte/Dify（config JSON + secret 分离），均为"通用元数据列 + 类型配置 JSON"，配置 schema 由 connector 自描述。

### 16.2 四条决策

1. **表收窄为通用列 + config JSON 列**：保留 id/connection_id/connection_name/connector_type/enabled/审计/del_flag；新增 `config text`（明文 JSON，非敏感参数全量放入，如 endpoint/username/ipWhiteList/timeout/retryCount）；删除 6 个 BPM 平铺列。加 connector 类型零 DDL。
2. **敏感字段拆入独立加密列**：新增 `credentials text`，存加密后的 JSON（可容纳 password/token/secretKey 等多敏感字段，不为单个密钥开专有列），用 RuoYi-Vue-Plus 自带 `@EncryptField` 字段级加密；哪些字段敏感由 connector descriptor 的 `ConfigField.sensitive`（新增元数据）声明，`BpmHttpConnector.describe()` 标记 password。Service 保存时按标记拆分 config/credentials，组装 Connection 时合并。
3. **本阶段只做后端归位，前端不动态化**：步骤 7 的写死平铺表单、BO 平铺入参保留（平铺 ↔ config 的组装留在后端）；"descriptor HTTP 端点 + schema 驱动动态表单"推迟到第二种 connector 出现或物料市场启动时再做。
4. **列形态用 text 不用 MySQL 原生 JSON**：配置不按内部字段做 SQL 查询（查询条件都在通用列），text 存 JSON 字符串无需 MyBatis TypeHandler，与既有 ipWhiteList 做法一致。表无业务数据，直接改建表 SQL，不写迁移脚本。

### 16.3 返工范围

- `script/sql/databus_connection.sql`：重写表结构（通用列 + config + credentials；菜单模板保留 §15 修正版）
- 后端：`SysDatabusConnection` 实体（删 6 平铺列、加 config/credentials 两字段，credentials 加 `@EncryptField`）、BO/VO（本阶段平铺契约保留，Service 内组装/拆解）、`SysDatabusConnectionServiceImpl`（删 `buildConfigMap`，改 config JSON ↔ Map 直读直写 + 敏感字段按 descriptor 拆并）、`ConnectorDescriptor.ConfigField`（加 sensitive）、`BpmHttpConnector.describe()`（password 标 sensitive）
- 前端：本阶段零改动；返工后回归验证 CRUD、测试连接、编辑回显
- 验证：mvnw compile + oxlint/vue-tsc；用户实测 6 端点与 5 步全链路

### 16.4 落地记录（2026-09-18 返工完成）

实际改动 7 文件：

| 文件 | 改动 |
|---|---|
| `script/sql/databus_connection.sql` | 表重写为 7 通用列 + `config text` + `credentials text`（drop 重建，无历史数据迁移）；菜单模板不变 |
| `ConnectorDescriptor.java` | `ConfigField` 加 `boolean sensitive` + 链式 `sensitive()` 方法（Lombok @Data 同时生成 isSensitive/setSensitive） |
| `BpmHttpConnector.java` | describe() 中 `authPassword` 字段标记 `.sensitive()`，其余 5 字段进明文 config |
| `SysDatabusConnection.java` | 删 endpoint/username/password/ipWhiteList/timeout/retryCount 6 字段；加 `config`、`credentials`（裸 `@EncryptField` 走全局 yml） |
| `SysDatabusConnectionServiceImpl.java` | 全量重写：删 `buildConfigMap`/`MapstructUtils.convert`；保存走 `buildEntityFromBo`（平铺→运行时 Map→按 descriptor sensitive 集合拆两份 JSON）；查询改 `selectPage`/`selectById` 实体后 `entityToVo` 手工回平铺；运行时注入 `toRuntimeConnection` 合并两份 JSON；BPM 平铺映射集中在 `boToRuntimeMap` 单一位置 |
| `ruoyi-modules/ruoyi-databus/pom.xml` | 新增 `ruoyi-common-encrypt` 依赖（首次编译失败暴露：模块此前未引加密 starter） |
| `ruoyi-admin/.../application.yml` | `mybatis-encryptor.enable: false→true`、`algorithm: BASE64→AES`、`password` 填开发默认密钥（**生产必须更换**）；全局开关影响面：仅 demo 模块 TestDemoEncrypt 同用此机制，无业务影响 |

实现要点：

1. **敏感拆分由 descriptor 驱动不硬编码**：`sensitiveKeys(connectorType)` 从 registry 取 descriptor 的 configSchema 过滤 sensitive 字段；未来新 connector 只在自己 describe() 标记。
2. **密码回显策略（本阶段拍板）**：编辑/列表 VO 仍回填解密密码（保持步骤 7 前端写死表单零改动、行为与平铺模型一致）；"密码留空=不修改"推迟到动态表单阶段。
3. **加解密兼容性**：框架实现对不带密文头（ENCRYPT_HEADER）的值原样返回，开关切换/历史明文数据读取不报错、无需迁移；开关关闭时功能正常但凭据退化为明文 JSON（仅本地开发）。
4. **空值与默认值**：timeout/retryCount 默认值在 `boToRuntimeMap` 兜底（30000/0）；credentials 无敏感字段时存 `"{}"`；ipWhiteList 以 List 形态进 config JSON，回显时再序列化为 JSON 数组字符串给 textarea。
5. **BO/VO 的 @AutoMapper 保留**：与实体仍有同名通用列可映射，Service 不再依赖自动映射（全手工组装），分页改走 `selectPage` 实体流。

验证：`mvnw -pl ruoyi-modules/ruoyi-databus -am -o compile` BUILD SUCCESS（首轮因缺 common-encrypt 依赖失败，补依赖后通过）；前端无文件改动，未重复跑前端检查。

用户实测前置（相比 §15.6 的差异项）：①**重新执行 databus_connection.sql**（表结构已变，脚本含 drop table，旧数据会清空——1D-P0 无生产数据）②重启后端使 yml 加密开关生效 ③验证点新增：DB 中直接查看 credentials 列应为带加密头前缀的密文、config 列可读；编辑回显密码正常；保存后执行链路 5 步全过（证明 config+credentials 合并反序列化 BpmHttpConnectionCfg 正确）。

## 17. RDS_EXECUTE / IDCARD_TO_USERID 端点（2026-09-19 同批落地）

> 状态：代码完成（BPM app 独立仓 + RuoYi feature/databus + plus-ui feature/databus），action.xml 两份部署副本已同步为 10 端点版；待用户在 IDEA 编译部署 class、重启 BPM 后实测。

老系统组件盘点结论：旧 RdsConfigProcessor 与规则引擎 @sqlValue（SqlValueProcessor）合并为一个 RDS_EXECUTE 组件（一需求一 comp，SQL 执行是同一原子能力）；旧 IdCardToUserIdProcessor 用户确认仍在用，单独成 IDCARD_TO_USERID 组件。

### 17.1 RDS_EXECUTE

请求体（`RdsExecuteRequest`，两端 DTO 字段一致）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| rdsId | String | 是 | BPM 后台「注册数据源」中配置的 RDS ID |
| method | String | 是 | 见下表 8 值；非法 method 抛 BPM_RDS_METHOD_INVALID |
| sql | String / String[] | 是 | SQL 原样透传（不做路径解析）；batch 多 SQL 模式传数组 |
| args | Object[] / Object[][] | 否 | 参数值（自包含，全部在 RuoYi 组件层 resolve 后传入）；batch 单 SQL 批量参数时为数组的数组 |
| fetchSize | Integer | 否 | >0 才应用（Spring JdbcTemplate） |
| maxRows | Integer | 否 | >0 才应用 |

| method | 返回 data |
|---|---|
| getString | String 标量 |
| getInt / getLong / getDouble | 数值标量 |
| getMap | 单行 Map |
| getMaps | List<Map> |
| update | 影响行数 int |
| batch | int[]，每次执行行数；两模式：多 SQL 无参 / 单 SQL + List<List> 批量参数（DynamicBatchSetter 从旧代码平移） |

响应：`{method, data}`（data 由 BPM 端 JSON.toJSON 后透传）。异常不吞（旧 RdsConfigProcessor catch 只 log 的问题修正），统一由 invokeService 转 ResponseObject。

RuoYi 侧组件 `rdsExecute`（`RdsExecuteComponent` + `RdsExecuteCfg`）：method 默认 getMaps；args 递归走 resolveParam（裸路径/混合模板/常量，嵌套 List 递归以支持批量参数）；响应存 `$.<tag>.method` 与 `$.<tag>.data`。

### 17.2 IDCARD_TO_USERID

老系统语义：字段级组件，meta 配多个 path，每个 path 是逗号分隔的身份证号串，查 BPM 组织用户表换 userId 后原地写回同一路径。

- 请求体：`{idCards: String[], separator: String}`（separator 默认 ","）
- BPM 端走 `com.actionsoft.bpms.util.DBSql` 执行 `select userid from orguser where ext1=?`（系统表不能用 BOAPI；工具类 IdCardQueryUtil 现为 userId↔idCard 双向查询），逐个查、查不到进 missed，不报错
- 响应：`{userIds: String（按 separator join）, matched: [{idCard, userId}], missed: [idCard...]}`；idCards 为空抛 BPM_PARAM_MISSING

RuoYi 侧组件 `idCardToUserId`（`IdCardToUserIdCfg.fields[].path/separator`）逐字段处理：path 值为空只 warn 跳过（与旧系统一致）；**全部未命中抛 ServiceException**（比旧系统静默 log 收紧，防空值覆盖业务字段）；部分未命中 warn 并照常写回命中 userId 到原 path。

### 17.3 落地清单与验证

- BPM app 仓：BpmErrorCode +2、2 DTO、2 Service、IdCardQueryUtil +1 方法、Controller +2 @Mapping、action.xml 源文件 +2 cmd-bean；两份部署副本（apps/install 与 webserver/webapps/portal/apps，后者为运行时实际加载）已同步，均含 `<param name="body" type="body"/>`（2026-09-18 缺 body param 导致"request 不能为空"的教训）
- RuoYi：2 DTO、BpmConst +2 常量、BpmHttpConnector +2 方法（describe 同步）、component/bpm 下 2 Cfg + 2 Component；`mvnw -pl ruoyi-modules/ruoyi-databus -am compile` BUILD SUCCESS
- plus-ui：cmp-defs 业务组件组 +2 物料（rdsExecute/ph:table/绿、idCardToUserId/ph:identification-card/青），CmpProps DATA_HINTS +2 示例 JSON；oxlint 0 error；vue-tsc databus 无新增报错（仅 monitor/logininfo 2 个历史 TS1149 基线）

用户实测要点：①IDEA 编译部署 BPM app class + 重启 BPM（action.xml 重启才加载）②rdsId 必须是 BPM 后台已注册的数据源 ID ③idCard 用 ORGUSER.EXT1 真实数据测，覆盖命中/部分未命中/全未命中（全未命中链路应报错中断）三场景。
