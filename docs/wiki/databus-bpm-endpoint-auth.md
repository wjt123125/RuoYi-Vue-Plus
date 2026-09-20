# BPM Connector 端点鉴权方案设计

> 状态：**调研完成，推荐 B（/portal/openapi 网关）为主线、C 兜底**（2026-09-20 修订：按用户准则"长远技术合理性优先、不考虑改造量"翻转推荐）。剩两项网关实测后拍板，本文不含代码改动。
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

### 3.2 平台 OpenAPI 网关（/portal/openapi + access_key + HmacMD5）

> 2026-09-20 补充：已抓取官方文档六页核实（docs.awspaas.com《AWS PaaS API Guide》HTTP 章节，2024-11-27 更新：获取密钥 / 浏览服务 / 签名 URL 请求 / Java 客户端 / 附录·发布 Web API / 附录·发布 RESTful API）。
> **用户已在本现场 63ga 控制台确认"身份策略（Secret Identity）"功能存在**，并创建 HTTP 类型策略：access_key=`databus`，绑定总线连接器 app（com.awspaas.user.apps.databus.connector 1.0.1）。secret 为现场自设值，**上线前应轮换为强随机串**。

平台自带一套面向外部系统集成的签名网关，**本机部署存在其服务端代码**：

- 服务端入口：`com.actionsoft.webframework.servlet.DispatcherOpenAPIServlet`，注解 `@WebServlet(urlPatterns="/openapi")`（javap 实证）+ portal 应用 context 路径 → 实际 URL `http://<bpm-host>:<port>/portal/openapi`（官方文档示例一致；6.4.1+ 另提供 `/portal/api`）。
- 该 servlet 接受两种凭证形态：表单参数 `access_key`+`sig`，或 HTTP `Authorization` 头（prepareMessage 字节码实证：`sig` 参数缺失时读 `Authorization` 头放入交换报文）。
- 引擎侧配套：`HandlerType { NORMAL, OPENAPI, RESTFUL }`（javap 实证枚举三值）；`@Controller.type()` 默认值 = **NORMAL**（注解 AnnotationDefault 实证）——我们 connector app 现在的 `@Controller` 无参写法即 NORMAL，这正是 12 个端点不在网关体系内、从而裸奔的根因。
- **自定义 app cmd 是网关一等公民（官方文档正面确认，此前"未实证"销账）**：`@Controller(type = HandlerType.OPENAPI, apiName = "...")` + `@Mapping("xxx.yyy")`，jar 放应用 lib 后在 **CC 连接服务 > 发布 > HTTP API** 发布并绑定身份策略，即可经网关调用；官方还提供 API 在线文档浏览与上下线开关（6.4.1+ 增加访控/流控策略与 swagger 在线测试，63ga 可用性以现场为准）。
- **官方 HTTP API（BO API、Process API、ORG API 等）同样经此网关**，需管理员手动发布并绑定身份策略；官方明确"Web API 不支持无身份策略调用"——调用必须携带某身份策略的 access_key（被调 API 未绑定特定策略时，任意有效策略均可通过）。这意味着总线将来直连平台原生能力时，认证体系与本方案无缝衔接。
- RESTFUL 通道（6.3.GA+）：JAX-RS 风格，认证为 HTTP Basic 且不支持无身份策略调用——Basic 每次传输凭据、无时间窗、无防重放，是签名机制的技术下位替代（评估见 §4 方案 B2 备注）。
- 客户端：`com.actionsoft.bpms.api.OpenApiClient`（aws-api-client.jar 在 63ga 介质三处随附：aws_lib、bin/lib、portal/commons/web-api）；官方文档将 Java 客户端章节标注为 6.4.1+ 适用，63ga 上自实现签名（约 30 行）最稳。

官方协议规格（官方文档 + 客户端字节码双向核实）：

| 项 | 值 |
| --- | --- |
| 入口 | `http://<bpm-host>:<port>/portal/openapi`（63ga；6.4.1+ 亦可用 `/portal/api`） |
| 方法 | **POST（application/x-www-form-urlencoded;charset=UTF-8）**——OpenApiClient.exec 字节码实证：全部参数（公共+业务）拼 form body 发送，非 URL query、非 JSON body |
| 公共参数 | `cmd`（必填）、`access_key`（必填）、`sig`（必填）、`sig_method=HmacMD5`、`format=json`、`timestamp`（毫秒） |
| 防重放 | **时间窗 5 分钟**（官方签名文档："被签名的 URL 必须在 5 分钟内到达，逾期返回 403"；早期社区文档写 6 分钟，实际容差以现场实测为准——客户端每次调用现算 timestamp 则不受影响）；官方无 nonce |
| 业务参数 | form 字段提交；**复杂对象/数组 JSON 序列化为一个参数值**（官方明示的标准模式——我们现有 `body` JSON 串恰好就是该形态，且作为参数参与签名，完整性受保护） |
| 密钥发放 | 管理员在 CC 连接服务 > 策略创建"身份策略"（控制台 UI 名 Secret Identity，类型 HTTP/SOAP），access_key 与 secret 均自定义；secret 只用于签名、不上链路 |

签名算法（反编译 `ApiUtils.makeSig` 逐指令核实，与官方签名文档一致）：

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

### 方案 B：走平台 /openapi 网关（access_key + HmacMD5）——**长远技术合理性最优，推荐主线**

做法：BPM 端 Controller 改为 `@Controller(type = HandlerType.OPENAPI)`，12 个端点在 CC 连接服务 > 发布 > HTTP API 发布并绑定「databus」身份策略（过渡期全量发布，SESSION_CREATE 后续退役）；RuoYi 侧 callBpm 改调 `/portal/openapi`，form-urlencoded 提交 + 手工 HmacMD5 签名（约 30 行，JDK 原生 Mac，零新依赖）。

此前三处"未实证"点，官方文档已销账两处半（见 §3.2）：

1. ~~网关是否开通~~ → 用户已确认 63ga 控制台有身份策略功能并成功创建密钥；剩"网关连通性"一项 Postman 实测（§9）。
2. ~~自定义 cmd 能否走网关~~ → **官方文档明示的正式用法**（type=OPENAPI + CC 发布 + 绑定策略），不再是未知机制；剩"本 build 发布/绑定 UI 实操确认"。
3. ~~请求形态适配~~ → 复杂参数 JSON 串化是官方明示的标准模式，现有 `body` 串恰好是该形态且参与签名；action.xml 的 body 注册改普通 param（三份部署副本同步）。

长期技术收益（按"不考虑改造量"准则选它的核心理由）：

- **密钥生命周期归平台**：发放/轮换/吊销是管理员控制台操作，与代码发版解耦（C 方案轮换要改配置文件随 app 重启）；secret 不上链路。
- **一套机制通吃未来**：官方 HTTP API（bo.query、process、ORG…）可发布到同一网关用同一把密钥；升级 6.4.1+ 后自动获得官方 Java SDK、访控/流控/上下线策略、swagger 在线测试。
- **架构净化**：签名接管传输认证后，SESSION_CREATE 的模拟会话与可绕过 IP 白名单（§1.1）整体退役成为候选——机机调用不再需要持有 sid，需要业务身份时 DTO 传 uid/idCard、服务内 UserContext 取用。
- 切 `type=OPENAPI` 后 12 个 cmd 预期从 `/portal/r/jd` 的 NORMAL 分发空间移除，裸奔路径自然消失（切换后实测确认）。

明确接受的弱点：HmacMD5 老旧（HMAC 构造不依赖 MD5 抗碰撞，叠加 HTTPS 后无实用攻击面，但非现代最佳实践，随平台演进）；无 nonce，**5 分钟窗内理论可重放**（内网 + D 网络收敛下风险可接受）；63ga 无访控/流控策略（6.4.1+ 才有）。

> 附：方案 B2（RESTFUL 通道 + HTTP Basic）——JAX-RS 风格重写端点，认证为 Basic：每次传输凭据、无时间窗、无防重放，技术上是签名机制的下位替代，仅作记录不采用。

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
| 防篡改 | ❌ | ✅ 签名（含 body 参数） | ✅ 签名（body 摘要） | ❌ |
| 防重放 | ❌ | △ 5 分钟窗、无 nonce | ✅ 时间窗+nonce | ❌ |
| 本现场可落地 | ✅ | ⚠️ 密钥已建（databus），剩网关连通 + 自定义 cmd 两项实测 | ✅ | ✅ |
| BPM 端自研量 | 无 | 无自研鉴权（注解 + CC 发布） | 小（一个守卫类） | 无 |
| 密钥/凭证管理 | BPM 用户密码 | ✅ 控制台身份策略（轮换/吊销免发版） | 双方各自配置（轮换随 app 重启） | 无 |
| 长期演进 | ❌ 机机调用滥用会话语义 | ✅ 平台主干：官方 API 同网关同密钥、6.4.1+ SDK/访控/流控 | △ 永久自维护迷你网关 | 无 |
| 改造面（按用户准则不作决策权重） | RuoYi 侧会话管理 | 注解/返回类型 + CC 发布 + action.xml 三副本 + RuoYi 调用层 | Controller 入口 + RuoYi 调用层 | 部署配置 |
| 与未来物料化/热插拔 | 一般 | 好 | 好 | 无影响 |

## 5. 推荐：B（/portal/openapi 网关）为主线，C 兜底，D 与 HTTPS 必做

> 2026-09-20 修订：用户拍定决策准则——**长远技术合理性优先，不考虑当下改造量**。在此准则下，推荐由"B/C 等权待实证"修订为 **B 主线**：机器认证是平台已经设计好的领域，OPENAPI 网关 + 身份策略在密钥生命周期管理、演进路线（官方 API 同网关同密钥、6.4.1+ SDK/访控/流控）、架构净化（SESSION_CREATE 与可绕过 IP 白名单退役）上全面占优；C 仅剩的两处边际优势（HMAC-SHA256 算法、nonce 防重放）在 HTTPS + 内网收敛下接近于零，长期代价却是永久自维护一个迷你网关（密钥文件、轮换机制、错误码、文档全要自己养）。

决策分叉：

1. 用户做 §9 的两项 Postman 实测（网关连通 + 自定义 cmd 试验包）；
2. 通过 → **B 主线**，实施要点见 §7；
3. 不通（网关 404/机制性不可用）→ **落 C**：§6 细节已备，全部基于已核实事实，没有平台能力假设；
4. 无论 B/C：上线前叠加 D（网络层限制来源 IP）+ HTTPS（或内网网段由反向代理终结 TLS）。

下文 §6 保留 C 的可实施粒度（兜底备用）；§7 为 B 主线实施要点。

## 6. 方案 C 详细设计（兜底，仅当 B 实测不通时启用）

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

## 7. 方案 B（主线）实施要点备忘

- **BPM 端**：
  - `@Controller(type = HandlerType.OPENAPI, apiName = "Databus API")`；12 个 `@Mapping` 名已是点分全局唯一，直接可用；
  - 方法返回类型迁到 `ApiResponse` 子类（StringResponse/MapResponse/ObjectResponse…，`ResponseObject` 不在官方 OPENAPI 契约内），错误经 `errorCode/msg` 表达（ErrorType）；
  - 取参从原始 body 改为 form 字段（复杂结构仍收 `body` JSON 串一个字段，`@Param` 声明）；action.xml `<param name="body" type="body"/>` 改普通 param，**三份部署副本同步**（源文件、apps/install、webserver）；
  - CC 连接服务 > 发布 > HTTP API 逐个发布并绑定「databus」身份策略；文件上传大 body 在 form 编码下的体积/编码需回归。
- **RuoYi 侧**：callBpm 从 POST JSON `/portal/r/jd?cmd=` 改为 POST form-urlencoded `/portal/openapi`，公共参数 cmd/access_key/timestamp/sig_method/format + 业务参数（含 `body` JSON 串）+ sig；签名按 §3.2 四步用 JDK `Mac`（HmacMD5）自实现约 30 行——63ga 不建议引 aws-api-client.jar（官方 Java 客户端章节标 6.4.1+，引介质内旧 jar 有版本错配风险）；access_key/secret 存 Connection credentials（`@EncryptField` 已加密）。
- **组件层零改动（connectId 引用模型的隔离红利）**：11 个 BPM 原子 comp 统一走 `cfg.getConnectionId()` → `databusContext.getConnection(id)` → `BpmHttpConnector`，协议/签名/密钥细节全部封在 Connector 内；过渡期 authType（jd/openapi）双协议分支同样只存在于 callBpm 内部。存量链路组件参数里的 connectionId 全部继续有效——切换只需编辑同一条 Connection 补密钥字段，不重建链路/物料。Cfg 字段增减：+accessKey/apiSecret（credentials），authUser/authPassword 过渡期保留、随 SESSION_CREATE 退役，ipWhiteList 删除（SESSION_CREATE body 传参专用）。
- **防重放**：5 分钟窗、无 nonce，接受（内网 + D 收敛 + HTTPS）；不建议在服务端再自加 nonce 校验——那等于把 C 混进 B，两头维护。
- **T5（业务 uid 由 DTO 声明）**：63ga 无访控策略，一期靠"唯一调用方密钥 + 网络收敛"约束；升级 6.4.1+ 后可用访控注解（@PermUser 等）收编。
- **过渡节奏**：切 type 后旧 `/portal/r/jd` 路径预期失效，会打断正在跑的 mock 全量实测——**等主线 mock 收尾后再切**；或 RuoYi 侧 callBpm 先做 authType 分支（jd/openapi 双协议并存一期），再切 BPM 端。
- **SESSION_CREATE**：网关上线后为退役候选（见方案 B 优点）；过渡期保留，testConnection 可继续用它，或改 ping 网关极简 cmd。

## 8. 影响面清单

| 仓库/位置 | 方案 C 改动 | 方案 B 改动 |
| --- | --- | --- |
| com.awspaas.databus.connector（独立仓） | 新增 AuthGuard + 鉴权错误码 ×6；invokeService 接入；配置文件示例；（可选）AUTH_PING 第 13 端点 | 注解改 OPENAPI + 返回类型 ApiResponse 化 + 取参 form 化 + action.xml 改普通 param |
| connector 部署副本（mldataboard 大仓工作区，**只同步不提交**） | class/action.xml/配置文件副本同步 | 同左 |
| RuoYi-Vue-Plus（ruoyi-databus） | Cfg + descriptor schema + callBpm 签名 | callBpm 改 /portal/openapi form 协议 + 手工 HmacMD5 签名（自实现约 30 行） |
| plus-ui | 连接管理表单加 authType/accessKey/apiSecret 三项 | 连接管理表单加 access_key/secret 字段 |
| BPM 控制台 | 无 | 发布 12 个 cmd 并绑定 databus 身份策略（策略已建 2026-09-20；SESSION_CREATE 后续随退役下线） |
| 网络部署 | httpd/防火墙来源 IP 收敛（D） | 同左 |

## 9. 待办：两项网关实测 + 拍板

1. **网关连通实测（用户 Postman，AI 不代跑，纯控制台操作零代码）**：
   - 在 CC 连接服务 > 文档 > Web API 里挑一个（或按官方文档"发布官方 HTTP API"流程发布一个）内置 cmd，如 `app.install.check`，绑定 databus 身份策略；
   - 按 §3.2 协议 POST `http://<bpm>/portal/openapi`（form-urlencoded，手工算 sig）；
   - **返回 JSON（非 404）即网关可用**；401/签名错则核对算法与时钟；404/机制性不可用 → 直接落 C。
2. **自定义 cmd 实测（需要我出一个 type=OPENAPI 试验包，动手前等你指令）**：CC 发布 SESSION_CREATE（或 BO_QUERY）绑定策略后按协议试调，确认 63ga 发布/绑定 UI 与文档一致、ApiResponse 迁移形态可行。
3. 实测通过 → 按 §7 实施 B；排期**避让主线 mock 全量实测**（过渡节奏见 §7）。
4. 保留事项：TLS 网段条件（httpd 反代终结 or 直连 HTTP + 网络层兜底）；secret 上线前轮换强随机；SESSION_CREATE/testConnection 定位随实施细化。

## 10. 明确不做

- 不做终端用户级鉴权/细粒度权限（那是 RuoYi 侧 Sa-Token 和链路可见性管理的事）。
- 不做 OAuth2/OIDC（平台不支持、现场无 IdP，过度设计）。
- 不把密钥塞进 BPM 数据库自定义新表做一期（文件配置 + 多 key 轮换足够单调用方场景）。
- 不在本期修复"业务 uid 由请求体声明"的模型（key→uid 白名单已提供约束手段；彻底改成平台会话身份是方案 A 的方向，已评估排除）。
