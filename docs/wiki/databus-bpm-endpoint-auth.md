# BPM Connector 端点鉴权方案设计

> 状态：**调研完成，待用户拍板**（2026-09-20）。本文只做方案设计，不含代码改动。
> 关联：[databus-bpm-connector-service-design.md](databus-bpm-connector-service-design.md) §9（四端点架构决策）、work-state.md「BPM 端点鉴权方案」待办。

## 1. 背景：现在为什么是裸奔

BPM 端 connector app 的 12 个端点全部声明 `session=false`：

```java
@Mapping(value = "com.awspaas.databus.connector.BO_CREATE",
        session = false,
        noSessionEvaluate = "无安全隐患",
        noSessionReason = "总线连接器端点")
```

- `session=false` 是 AWS PaaS 的**免登录接口**开关，平台不做任何会话校验。官方文档（BPM文档.md §17.7）明确要求：免登录接口"必须自行校验请求合法性（如签名验证、IP白名单等）"。我们目前什么都没做。
- 调用入口 `POST /portal/r/jd?cmd=com.awspaas.databus.connector.XXX`，任何能访问 BPM 端口的主机都能直接调 BO_CREATE/RDS_EXECUTE 等写操作端点。
- 旧系统（com.awspaas.user.apps.data.bus）同样是 `session=false`，传输层也无鉴权，仅靠请求体里的 `userInfo.idCard` 模拟用户身份——知道一个身份证号就能以该人身份调用，不是认证，只是身份声明。

唯一带真实凭证校验的是 SESSION_CREATE（校验 BPM 用户名+密码），但它只换 sid，**其余 11 个端点不校验 sid，也不要求带 sid**。

### 1.1 关键安全发现：现有"IP 白名单"是自证清白

[SessionCreateService.java](file:///e:/01.code/Actionsoft/release/src/com.awspaas.databus.connector/src/com/awspaas/databus/connector/service/SessionCreateService.java) 的 IP 白名单校验存在双重绕过：

1. `ipWhiteList` 由**请求体传入**（决策 9.1.7），不是 BPM 端服务端配置——攻击者直连端点时可以在 body 里自带任意白名单；
2. 校验用的 `clientIp` 也取自 `request.getClientIp()`（**请求体字段**，L67/L80），而 BPM 容器注入的 `clientIp` 方法参数（真实 socket 来源 IP，见 BPM文档.md §17.3）在 Controller 里被忽略。

即攻击者构造 `{clientIp: "<白名单内任意IP>", ipWhiteList: ["<同一IP>"]}` 即可通过。该校验只约束"RuoYi 总线按配置发起的调用"，对直连无效，**不能计为安全措施**。

## 2. 威胁模型与设计目标

| 编号 | 威胁 | 对策归属 |
| --- | --- | --- |
| T1 | 内网任意主机/被攻陷主机直接调用 12 个端点 | 调用方身份认证（本文核心） |
| T2 | 报文链路被抓包、篡改 | 签名完整性 + HTTPS |
| T3 | 截获一个合法请求后重放 | 时间戳窗口 + nonce 一次性 |
| T4 | 凭据（密钥/密码）泄露 | 加密存储、可轮换、绑定范围 |
| T5 | 以任意 BPM 用户身份操作（uid 由请求体声明） | key 绑定 uid 白名单（纵深） |
| T6 | BPM 端口/路径被公网或办公网任意访问 | 网络层隔离（无论选哪种鉴权都必须做） |

设计目标：

1. 服务对服务（RuoYi 总线 → BPM app）的**机器认证**，不做终端用户认证——终端用户认证在 RuoYi 侧已由 Sa-Token 解决。
2. 业务身份（以哪个 BPM uid 执行）维持现状：uid 在请求 DTO 中显式声明，但必须纳入鉴权约束（T5）。
3. 不依赖在该客户现场可能未授权（license）的平台组件；方案要有平台原生与自研两条可落地路径。
4. 密钥管理复用连接存储模型（[databus-bpm-connector-service-design.md §16](databus-bpm-connector-service-design.md)：config/credentials 双 JSON + `@EncryptField`），不另起炉灶。

## 3. AWS PaaS 平台原生能力实证

> 证据来源：本机部署 `e:\01.code\Actionsoft\release`（AWS PaaS 6，2020 年版平台 jar），用 JDK 自带 javap 对平台 class 反编译 + 官方文档。
> 平台引擎经 Allatori 混淆，字符串加密处不做深挖；以下结论均有可复核的类/注解/官方文档证据。

### 3.1 平台 session（sid）机制

- `@Mapping` 的 `session` 属性默认 `true`：平台对调用做登录 Session 校验。校验所需 sid 从请求参数 **`sid`** 读取（[DispatcherRequest.class](file:///e:/01.code/Actionsoft/release/aws_lib/aws-infrastructure-core.jar) 字节码常量 `String sid` 实证），调用形如 `/portal/r/jd?cmd=xxx&sid=<sid>`。
- 建链：`SDK.getPortalAPI().createClientSessionByDevice(userId, password, lang, clientIp, device)`，我们 SESSION_CREATE 已在用；sid 与 clientIp 绑定。
- 服务端可用 API（`com.actionsoft.bpms.server.UserContext` / `SSOUtil`）：
  - `UserContext.fromSessionId(String sid)` / `validateSession()`——凭 sid 还原并校验会话；
  - `SSOUtil.refreshSession(sid)`、`destroySession(sid)`、`registerClientSessionNoPassword(...)`——会话续期/注销/免密注册。
- `@Mapping` 注解实际还有两个文档未记载的属性：`authInfo()`、`scopeAccess()`（javap 实证），语义未在官方文档中查到，不纳入设计依赖。

### 3.2 平台 OpenAPI 网关（/openapi + access_key + HmacMD5）

平台自带一套面向外部系统集成的签名网关，**本机部署存在其服务端代码**：

- 服务端入口：`com.actionsoft.webframework.servlet.DispatcherOpenAPIServlet`，注解 `@WebServlet(urlPatterns="/openapi", asyncSupported=true)`（javap -v 实证），即 URL 为 `http://<bpm-host>:<port>/openapi`（注意不带 `/portal/r/jd` 前缀）。
- 该 servlet 接受两种凭证形态：表单参数 `access_key`+`sig`，或 HTTP `Authorization` 头（prepareMessage 字节码实证）。
- 引擎侧存在配套类型：`HandlerType { NORMAL, OPENAPI, RESTFUL }`、`AppCmd.isApi()`、`AppCmd.getIgnoreSign()`（aws-infrastructure-core.jar 实证），表明平台的 cmd 分发器原生区分 OPENAPI 处理器，且支持逐参数豁免签名。
- 官方客户端：`com.actionsoft.bpms.api.OpenApiClient`（aws-api-client.jar，随平台分发），官方文档：《OpenAPI使用方法》（[看云 AWS_OpenAPI 文档](https://www.kancloud.cn/youngheart/awsopenapi/1374258)）。

官方协议规格（官方文档 + 客户端字节码双向核实）：

| 项 | 值 |
| --- | --- |
| 入口 | `Portal URL + /openapi` |
| 方法 | POST（form-urlencoded），GET 也可 |
| 公共参数 | `cmd`（必填）、`access_key`（必填）、`sig`（必填）、`sig_method=HmacMD5`、`format=json`、`timestamp`（毫秒，**与服务器时差不得超过 6 分钟**） |
| 业务参数 | 按字段 form 提交，复杂对象序列化为 JSON 字符串 |
| 防重放 | timestamp 6 分钟窗口（官方未提 nonce） |
| 密钥发放 | 管理员在控制台的密钥身份（AWS CC 连接服务-策略）中创建，access_key 与 secret **均自定义**，secret 只用于签名、不上链路 |

签名算法（反编译 `ApiUtils.makeSig` 逐指令核实）：

1. 取除 `sig` 及豁免集合外的全部参数，剔除 key/value 为空者；
2. 按参数名 **ASCII 升序**排序；
3. 拼接待签名串：先写 `secret`，再依次直接追加 `key+value`（**无分隔符**），即 `secret + k1v1 + k2v2 + ...`；
4. `sig = HMAC_MD5(key=secret, message=待签名串)` 转 **大写 hex**。

### 3.3 其他平台机制

- portal webapp 带 `cas-client-core-3.2.1.jar`，支持 CAS 单点登录——面向浏览器会话，不适合服务对服务调用，排除。
- 未发现平台级 Bearer Token / OAuth2 客户端模式（DispatcherOauth* 两个 servlet 是微信公众号 OAuth 网页授权流程）。

## 4. 候选方案

### 方案 A：切回平台 session（session=true + sid）

做法：12 个端点去掉 `session=false`；RuoYi 侧用 Connection 里的 authUser/authPassword 调 SESSION_CREATE 换 sid 并缓存，每次调用 URL 带 `sid`，失效时重新登录；可用 `SSOUtil.refreshSession` 续期。

- 优点：纯平台原生；调用天然以真实 BPM 用户身份执行，权限/审计沿用平台；零自研鉴权代码。
- 缺点：
  - sid 有生命周期（过期/互踢/IP 绑定），要处理缓存、续期、并发重试，连接器复杂度上升；
  - sid 出现在 URL 上，会进 Tomcat/反向代理 access log；
  - sid 是"设备会话"语义（createClientSessionByDevice），长生命周期被服务调用持有，泄露即等于账号失窃；
  - 仍然没有消息防重放/防篡改。

### 方案 B：走平台 /openapi 网关（access_key + HmacMD5）

做法：BPM 端不再直接暴露 `/portal/r/jd`，RuoYi 侧用 OpenApiClient 同款协议调 `/openapi`；管理员在 BPM 控制台发一个 access_key/secret，存入 Connection credentials。

- 优点：平台原生、为服务间集成设计；secret 不上链路；自带 6 分钟时间窗防重放；密钥在控制台管理、与应用代码解耦；我们几乎不用在 BPM app 里写鉴权代码。
- 缺点/未实证点（**三处必须先实测**，见 §9）：
  1. 该客户部署的 /openapi 是否可用（老版本可能依赖 license/未安装的 CC 组件；servlet 类存在≠功能已开通）；
  2. **自定义 app 的 cmd 能否通过 /openapi 调用**——引擎有 HandlerType.OPENAPI 支持，但自定义 @Mapping 的开放方式（应用安装描述符开关？自动开放？）在混淆代码里无法静态确认；
  3. 请求形态适配：openapi 收 form 字段（业务对象为 JSON 字符串），我们现在靠 action.xml `<param type="body">` 注入**原始 body**，12 个端点的取参方式和 action.xml（含三份部署副本）都要改。
- 附带弱点：HmacMD5 算法老旧（HMAC-MD5 目前无实用碰撞攻击，可接受，但不是最佳实践）；无 nonce，6 分钟窗口内可重放。

### 方案 C：应用层共享密钥 + HMAC-SHA256 自验（自研薄层）

做法：端点保持 `session=false`，在 connector app 内加一个集中鉴权守卫，校验 RuoYi 侧用共享密钥算出的签名。密钥 BPM 端服务端存放、RuoYi 端走 credentials 加密存储。

- 优点：不依赖任何 license/未证实能力；算法选 HMAC-SHA256 + 时间戳 + nonce，防重放完整；鉴权语义完全可控（可绑定 key→uid 白名单/IP 白名单）；对现有 12 端点侵入小（Controller 已有唯一入口 `invokeService`）。
- 缺点：自研代码（虽薄）；BPM 端密钥存储要自己设计；轮换/多密钥管理自己负责。

### 方案 D：纯网络层（IP 白名单/反向代理）

Tomcat `RemoteAddrFilter`、Apache httpd 或防火墙限制来源 IP；不暴露 `/portal/r/jd?cmd=com.awspaas.databus.connector.*` 到非授权网段。

- 优点：零代码、对上层透明。
- 缺点：只认 IP 不认身份，内网横向移动后形同虚设；无法防重放/篡改。**只能作为纵深，不能单独使用。**

### 4.1 对比总表

| 维度 | A sid 会话 | B /openapi 网关 | C 自研 HMAC | D 网络层 |
| --- | --- | --- | --- | --- |
| 调用方认证 | ✅ 平台会话 | ✅ 密钥签名 | ✅ 密钥签名 | ❌ 只认 IP |
| 防篡改 | ❌ | ✅ 签名 | ✅ 签名 | ❌ |
| 防重放 | ❌ | △ 6 分钟窗 | ✅ 时间窗+nonce | ❌ |
| 本现场可落地 | ✅ | ⚠️ 三处未实证 | ✅ | ✅ |
| BPM 端自研量 | 无 | 无（若直接支持自定义 cmd） | 小（一个守卫类） | 无 |
| 改造面 | RuoYi 侧会话管理 | 12 端点取参 + action.xml 三副本 + RuoYi 调用层 | Controller 入口 + RuoYi 调用层 | 部署配置 |
| 密钥/凭证管理 | BPM 用户密码 | 控制台发放 | 双方各自配置 | 无 |
| 与未来物料化/热插拔 | 一般 | 好 | 好 | 无影响 |

## 5. 推荐：B 优先实证、C 兜底，D 与 HTTPS 必做

建议的决策分叉：

1. **先花十几分钟做 §9 的 B 方案实证**（用户在 BPM 环境操作，AI 不代跑）。
2. 若 /openapi 在本现场可用且自定义 cmd 可达 → **选 B**：平台原生、免自研、密钥由平台管，长期最省心。取参适配工作量可控。
3. 若任一实证不通过 → **落 C**：方案细节见 §6，全部基于已核实的事实设计，没有平台能力假设。
4. 无论 B/C：上线前都要叠加 D（网络层限制来源 IP）和 HTTPS（或在内网网段由反向代理终结 TLS）。

下文 §6 把 C 写到可直接实施的粒度；§7 列出选 B 时的差异点，避免到时候重新调研。

## 6. 方案 C 详细设计（兜底主线）

### 6.1 协议

受 BPM 平台限制（@Mapping 方法只能拿到 `UserContext / RequestParams / clientIp / body`，**RequestParams 只能读参数、读不到 HTTP header**，javap 实证），鉴权字段只能放 **URL query 参数**：

```
POST /portal/r/jd?cmd=com.awspaas.databus.connector.BO_CREATE
     &_db_key=<keyId>
     &_db_ts=<epochMillis>
     &_db_nonce=<uuid>
     &_db_sign=<hex>
```

- `_db_ts`：发起方时间戳（毫秒）；BPM 端校验与服务器时差 ±5 分钟。
- `_db_nonce`：每次调用随机 UUID；BPM 端在时间窗内去重（一次性）。
- `_db_sign`：`lowercase(hex(HMAC_SHA256(secret, stringToSign)))`。
- 待签名串（`\n` 分隔）：

```
<HTTP方法，固定 POST>
<cmd 的值>
<_db_ts>
<_db_nonce>
<sha256Hex(原始请求体 body)>
```

body 整体进摘要，天然覆盖所有业务参数；query 参数只有四个固定名，无参数遍历歧义。

防重放缓存用 connector app 已随仓的 hutool（lib/hutool-all-5.8.38.jar）：`cn.hutool.cache.impl.TimedCache`（TTL = 10 分钟）+ `containsKey` 即拒。BPM 节点为单机/集群会话粘性部署，多节点时各节点缓存独立不影响安全性（只放大极小概率误拒，不放大攻击面）；将来真多活再换 Redis。

### 6.2 密钥模型与存放

一个 keyId 对应一组策略，BPM 端用**服务端配置文件**存放（一期），路径 `web/com.awspaas.databus.connector/databus-auth.properties`（随 app 部署，不进 git 仓库；仓库内只放 `.example`）：

```properties
# mode: none(过渡期/内网裸奔) | hmac(默认)
databus.auth.mode=hmac
# keyId=secret,允许的uid列表(逗号分隔,*为不限),允许的来源IP(逗号分隔,*为不限)
databus.auth.key.ruoyi-prod=<32字节随机secret>,*,10.20.30.40
```

- 密钥文件属主收紧、不入库；RuoYi 侧 secret 进 Connection 的 `credentials` JSON（已由 `@EncryptField` AES 加密），config 里只放 `authType/accessKey`。
- key→uid 白名单对应 T5：校验时从 body 解析各端点 DTO 已有的 uid 字段，不在白名单直接拒（一期可先 `*`，上线前收敛为固定服务账号）。
- key→IP 白名单对应 T1 纵深：来源 IP 用 BPM 容器注入的 `clientIp` 方法参数（socket 直连可信；若经 httpd 反代，需在 httpd 侧正确设置 XFF 并在平台配置可信代理后再用，一期直接在 httpd/防火墙做 D，应用层 IP 白名单留空）。
- 轮换：支持同时配置多个 keyId（天然支持双密钥平滑轮换：新旧并存 → 切 RuoYi 侧 → 删旧 key）；配置变更一期随 app 重启生效。

二期增强（不在本次范围）：密钥挪进 BPM BO 表 + 控制台页面管理、AUTH_RELOAD 热加载端点（该端点自身用旧签名鉴权）。

### 6.3 BPM 端校验点

- 集中在 [DataBusConnectorController.java](file:///e:/01.code/Actionsoft/release/src/com.awspaas.databus.connector/src/com/awspaas/databus/connector/controller/DataBusConnectorController.java) 的 `invokeService` 前置：新增 `AuthGuard.verify(requestParams, clientIp, cmd, body)`，12 个端点零改动（签名参数从 RequestParams 读，不占 action.xml body 注册）。
- 失败统一返回 ResponseObject err：
  - `BPM_AUTH_MISSING`（鉴权参数缺失）、`BPM_AUTH_KEY_INVALID`（keyId 不存在）、`BPM_AUTH_SIGN_INVALID`（签名不匹配）、`BPM_AUTH_TS_EXPIRED`（时间窗）、`BPM_AUTH_NONCE_REPLAY`（nonce 重放）、`BPM_AUTH_UID_FORBIDDEN`（uid 不在 key 的授权范围）。
  - 失败日志只记 keyId/cmd/IP/原因，**不记 secret、不记完整待签名串、body 摘要可留**。
- mode=none 时仅打 warn 日志（保留当前内测可用性），但启动日志醒目提示鉴权关闭。
- 新增第 13 个端点建议（可选）：`AUTH_PING`——无业务入参、签名通过即返回 `{pong:true, serverTime}`，供 RuoYi 侧"测试连接"使用，使测试连接不再依赖配置 BPM 用户名密码；不做的话 testConnection 继续走 SESSION_CREATE。

### 6.4 RuoYi 侧改造点

- [BpmHttpConnectionCfg.java](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmHttpConnectionCfg.java) 加 `authType`（`none`/`hmac`，默认 hmac）、`accessKey`、`apiSecret`（敏感字段）；[BpmHttpConnector#describe](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmHttpConnector.java) configSchema 同步，`apiSecret` 标 sensitive 自动进 credentials 加密列。
- `callBpm` 拼 URL 时附四个 `_db_*` 参数；签名用 JDK 原生 `javax.crypto.Mac`（HmacSHA256），不引新依赖；body 摘要在序列化后、发送前算，保证与 BPM 端逐字节一致（UTF-8）。
- authType=none 时不带签名参数（对接 mode=none 的过渡期 BPM 端）。
- 连接管理表单（ConnectionForm/Service 的平铺字段过渡期方案）加这三项；"留空不修改"仍按动态表单阶段的既有节奏。

### 6.5 迁移步骤建议

1. BPM 端发版支持 mode=none/hmac 双模式（默认 none 过渡，不打断现有 mock 实测）；
2. RuoYi 侧发版签名能力 + 配好 key；
3. 联调签名通过后，BPM 端切 mode=hmac 重启；
4. 上线前：httpd/防火墙收敛来源 IP，uid 白名单从 `*` 收敛为服务账号，HTTP 视网段情况升级 TLS。

## 7. 若选方案 B 的差异点备忘

- RuoYi 侧不用自己写签名算法，可直接把 aws-api-client.jar 作为 BPM connector 的编译依赖（该 jar 在 BPM 安装介质中，非中央仓库依赖，需手工 install 到本地仓或 shade 进模块）；不想引 jar 则按 §3.2 的 4 步算法自行实现（约 50 行）。
- BPM 端 12 个 Controller 方法的取参从"原始 body"改为"form 字段 body（JSON 字符串）"，action.xml 的 `<param name="body" type="body"/>` 改为普通 param（**三份部署副本同步**：源文件、apps/install、webserver）；需回归文件上传这类大 body 场景在 form 编码下的体积与编码。
- access_key/secret 由管理员在 BPM 控制台创建（老版本控制台菜单位置需现场找，新版在"应用开发-连接服务-策略"，见集简云授权文档截图描述）。
- 防重放只有 6 分钟时间窗、无 nonce；如不满足安全要求则仍需退回 C 或在 C 之外再包一层。

## 8. 影响面清单

| 仓库/位置 | 方案 C 改动 | 方案 B 改动 |
| --- | --- | --- |
| com.awspaas.databus.connector（独立仓） | 新增 AuthGuard + 鉴权错误码 ×6；invokeService 接入；配置文件示例；（可选）AUTH_PING 第 13 端点 | 12 端点取参改造 + action.xml |
| connector 部署副本（mldataboard 大仓工作区，**只同步不提交**） | class/action.xml/配置文件副本同步 | 同左 |
| RuoYi-Vue-Plus（ruoyi-databus） | Cfg + descriptor schema + callBpm 签名 | callBpm 改 openapi 协议（依赖 jar 或自实现） |
| plus-ui | 连接管理表单加 authType/accessKey/apiSecret 三项 | 同左（字段名换成 access_key/secret） |
| BPM 控制台 | 无 | 发放 access_key/secret |
| 网络部署 | httpd/防火墙来源 IP 收敛（D） | 同左 |

## 9. 待用户拍板 / 实证的问题

1. **B 方案实证（决定 B/C 分叉，用户在 BPM 环境操作）**：
   - 控制台能否找到创建 API 密钥（access_key/secret）的入口；
   - 用 Postman 按 §3.2 协议对 `http://<bpm>/openapi` 调一个内置 cmd（如文档里的 `app.install.check`）能否通——确认网关已开通；
   - 再对我们的一个自定义 cmd（如 `com.awspaas.databus.connector.SESSION_CREATE`）按 form 形态试调，确认自定义 app cmd 可达。
2. 方案 C 的一期取舍：uid 白名单上线时是否收敛为固定服务账号？IP 白名单放在应用层还是只做网络层 D？
3. SESSION_CREATE 端点在鉴权上线后的定位：它继续作为"链路业务组件"保留（链路里需要 sid/idCard 的场景），但传输认证不再依赖它；testConnection 是否改为新增 AUTH_PING。
4. BPM 与 RuoYi 之间是否有上 TLS 的网段条件（httpd 反代终结 or 直连 HTTP + 网络层兜底）。

## 10. 明确不做

- 不做终端用户级鉴权/细粒度权限（那是 RuoYi 侧 Sa-Token 和链路可见性管理的事）。
- 不做 OAuth2/OIDC（平台不支持、现场无 IdP，过度设计）。
- 不把密钥塞进 BPM 数据库自定义新表做一期（文件配置 + 多 key 轮换足够单调用方场景）。
- 不在本期修复"业务 uid 由请求体声明"的模型（key→uid 白名单已提供约束手段；彻底改成平台会话身份是方案 A 的方向，已评估排除）。
