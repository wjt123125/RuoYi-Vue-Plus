# HTTP 请求组件（httpRequest）完善化规格

> 模块：ruoyi-databus `org.dromara.databus.component.protocol`
> 前端物料：plus-ui `src/views/databus/editor/cmp-defs.ts`（httpRequest 组件）
> 拍板日期：2026-09-19（替换 1A 骨架档，对照旧总线 HttpRequestProcessor 全量完善）

## 1. 粒度决策：单组件 + 参数化，不按 method/datatype 拆

httpRequest **只做一个物料**，method 与请求体媒介都是节点参数（当前 JSON 配置承载，未来 schema 驱动表单工程统一切换），内部按媒介分策略类。

- 与项目既有范式一致：BpmHttpConnector 一个连接器承载 10 个端点操作——协议内聚、操作为参数，HTTP 同构。
- 画布收益：改 method/媒介只需改下拉，不用删节点重拖重连线；EL 中节点类型对分支/数据流不携带额外信息，拆类型零收益。
- 参照业界：n8n（HTTP Request 单节点，method + body type 下拉）、Node-RED、Mule、Camunda 均为单节点；NiFi 早年拆 PostHTTP 后废弃合并。
- "大组件变垃圾堆"靠内部结构治：`HttpRequestCfg` 纯数据、`HttpRequestComponent.process()` 只编排、body 序列化各媒介为独立策略类。

**真正该拆的语义边界**（与 idCardToUserId 独立成件的决策同理）：

- 有独立业务语义的调用 → 独立组件（idCardToUserId、未来的 wechatSendMessage），不是通用 httpRequest + 一坨配置；
- SOAP 信封/WSDL 语义不同 → SoapRequest 保持独立；
- 鉴权与公共 endpoint 复用 → 未来 Connection 模型（见 §7 推后清单）。

## 2. 能力面（本档）

- **method**：GET / POST / PUT / PATCH / DELETE（RestClient 原生支持；HEAD/OPTIONS 无取数场景，不做）。
- **bodyType**：
  - `none`（默认）：无请求体，GET/DELETE 常规搭配；
  - `json`：Map/List 递归做路径值解析后 JSON 序列化，Content-Type `application/json`；
  - `form`：Map 转 `application/x-www-form-urlencoded`（老式登录/token 接口刚需，旧系统反而没有）；
  - `raw`：字符串直发，Content-Type 手填（text/plain、裸 XML 等兜底 SoapRequest 之外的 XML 场景）。
- **query**：Map 拼 query string（UriComponentsBuilder，URL 编码由框架处理），null 值跳过；**值为 List/数组时序列化为同名重复参数**（`ids=1&ids=2`），值为对象无法表达直接报配置错。
- **headers**：Map 全量发送，值支持字面量/裸路径/混合字符串。
- **url**：支持混合片段（如 `http://x/$.userId/detail`），路径变量由 url 模板承载，不做独立 pathVariables 配置；变量值含中文/空格等做 UTF-8 编码（encode 不重复编码已有 %xx）；url 必须带 http(s) 协议前缀，否则报配置错。
- **响应字符集**：可选 `responseCharset`（如 `GBK`）；缺省按响应头 Content-Type charset、再缺省 UTF-8 解码，配了强制覆盖，兜底国内老接口。
- **响应头白名单**：`responseHeaders` 点名的响应头才抽到 `$.<tag>.headers.<头名小写>`，默认空数组不落任何头（分页 `X-Total-Count`、header 下发 token 等场景）。

## 3. 配置 schema（HttpRequestCfg）

```json
{
  "method": "POST",
  "url": "http://localhost:8080/auth/login",
  "headers": { "X-Tenant": "default" },
  "query":   { "from": "$.source" },
  "bodyType": "json",
  "body":    { "username": "admin", "password": "$.pwd" },
  "rawContentType": "text/plain",
  "auth":    { "type": "bearer", "token": "$.login.token" },
  "timeoutMs": 10000,
  "failOnHttpError": true,
  "responseCharset": "UTF-8",
  "responseHeaders": ["X-Total-Count"],
  "mappings": [
    { "field": "bizCode", "path": "$.code", "required": true },
    { "field": "msg", "path": "$.message", "required": false }
  ]
}
```

- `method` 缺省 GET；`bodyType` 缺省 none；`timeoutMs` 缺省 10000；`failOnHttpError` 缺省 true；`responseHeaders` 缺省空数组。
- `auth.type`：`none`（默认）/ `basic`（username+password，组件自动 Base64）/ `bearer`（token 自动拼 `Bearer ` 前缀）；所有值支持路径取值；auth 生成的 Authorization 与 headers 同名头冲突时以 auth 为准并打 warn。
- `mappings` 为对象数组：`field`（数据空间字段名）、`path`（响应体 JSONPath）、`required` 默认 **true**。

## 4. 响应出口契约

固定写入数据空间：

- `$.<tag>.status`：HTTP 状态码（int）；
- `$.<tag>.response`：响应体——JSON 自动解析为对象/数组，非 JSON 存原始字符串；**空响应体（204 等）存 null**；
- `$.<tag>.headers.<头名小写>`：仅 `responseHeaders` 白名单中点名的响应头。

mappings 抽取到 `$.<tag>.<field>`：

- `required=true`（默认）：响应中取不到路径**直接抛错**（对齐旧系统 `extractRequired` 意图：配置写错早暴露，不静默存 null 污染下游）；响应体为 null 时报"响应体为空，无法抽取字段"；
- `required=false`：取不到跳过。
- 保留名：`field` 不允许取 `status`/`response`/`headers`，避免覆盖固定出口。

不内置旧系统写死的 result/msg/code 业务三字段——用 mappings 抽出后交 condition 组件判断，协议组件不绑定任何业务响应规范。响应头默认不落（噪音大），仅按白名单抽取。

## 5. 错误处理

两类分开：

- **网络异常/超时**（连不上、读超时）：始终抛 ServiceException，信息含 method、url、异常原因；
- **4xx/5xx**：
  - `failOnHttpError=true`（默认）：抛 ServiceException，信息含 method、url、status、响应体前 500 字符——试运行结果里能直接看到对方报错；
  - `failOnHttpError=false`：不抛错，status 与错误响应体照常落 `$.<tag>.status/response`，由后续 condition 节点自行判分支（覆盖"登录失败也要取对方 msg"场景）。

## 6. 可靠性

- `timeoutMs` 单值（默认 10000ms），连接+读取合并控制，经 JdkClientHttpRequestFactory 设入 RestClient；不拆 connect/read 两值（业务用户不可区分）。
- **不做重试**：HTTP 重试绕不开幂等（POST 超时重试可能造两条单据），失败重跑属执行引擎层策略（roadmap 超时/重试/熔断位置），不由协议组件各自实现。

## 7. 实现细则（硬规则）

- **冲突优先级**：bodyType 推断的 Content-Type、auth 生成的 Authorization 为准；headers 手填同名头 → warn 并忽略，避免两份配置打架。
- **请求体类型校验**（发起前配置校验，错误信息带 tag）：
  - `form`：body 必须是 Map/List 解析后的 Map，否则报配置错并提示；
  - `raw`：body 解析后必须是字符串，配 Map/List 报错提示改用 json；`rawContentType` 为空时兜底 `text/plain;charset=UTF-8`；
  - `json`：body 是 Map/List 正常序列化；是字符串时视为 **JSON 原文直发**（信任用户，适配数据空间取出的 JSON 字符串）；
  - POST/PUT/PATCH 允许无体（bodyType=none）；GET/DELETE 配体不禁止（RestClient 层面可发送）。
- **重定向**：JDK HttpClient 默认不跟随，显式开 `followRedirects(NORMAL)`——否则 302 拿到空体，status 出口也无意义。
- **RestClient 实例**：按 timeoutMs 分键缓存（ConcurrentHashMap），不为每次请求新建 HttpClient（建造重、带线程池）。
- **日志**：info 输出 method、url、status、耗时、响应体前 500 字符；Authorization 请求头与 body/query 中的 password/token/secret 类字段脱敏（key 名匹配即掩码）。
- **query 多值**：Map 值解析后为 List/数组 → 同名重复参数；为对象 → 配置错。
- **响应解码**：优先 responseCharset 显式配置，其次响应头 charset，最后 UTF-8；空错误响应体在 4xx/5xx 上下文里显示 `<空响应体>`。

## 8. 推后清单（不砍，触发条件明确再做）

| 项 | 推后理由 |
| --- | --- |
| multipart/form-data 文件上传 | 需定义文件来源（base64/路径）；BPM 附件场景走 FILE_UPLOAD/FILE_DOWNLOAD 专用端点，不挤在此 |
| 二进制响应（图片/PDF 等） | 数据空间是 JSON 文档不适合装二进制，需要 base64/文件引用形态；附件走 BPM 专用端点 |
| OAuth2 / 自定义 apiKey 鉴权 | 配置形态各异、当前无真实接口需求 |
| HttpConnection 连接管理 | baseUrl+凭据多节点复用，与 BPM Connection 同构；物料市场阶段做，届时 auth 整体搬进连接实例，组件只引用 connectionId |
| HEAD/OPTIONS | 无业务取数场景 |
| 代理、自定义证书、Cookie 会话保持 | 旧系统没有，等部署环境真有约束再补（Cookie 保持随 HttpConnection 一起评估） |

## 9. 落地范围（重构）

- 后端：重写 `HttpRequestCfg` / `HttpRequestComponent`（`org.dromara.databus.component.protocol`），body 序列化抽策略类；mappings 旧字符串 Map 形态迁移为对象数组（前端同步，无存量线上数据）。
- 前端（2026-09-19 拍板）：**本档不做结构化条件子表单**——全部业务组件当前统一走 CmpProps 的 JSON 代码编辑器，单件开特殊分支会破坏一致性；schema 驱动表单作为独立工程后续统一立项。本档仅更新 cmp-defs 描述、CmpProps 的 DATA_HINTS 占位示例（五动词/三媒介/auth/mappings 新格式），mock-presets 主示例（GET /auth/code 单节点）保持可一键运行；另补「免授权双请求」示例（GET /auth/code 做 required+optional mappings 抽取，GET / 演示非 JSON 纯文本响应落盘），不依赖任何登录态与加密开关。2026-09-19 实测确认：本框架 POST 免登录接口（/auth/login、/auth/register）全部挂 @ApiEncrypt，api-decrypt.enabled=true 时裸 HTTP 打它们会被 CryptoFilter 以 HTTP 200 + 信封 code:403 拒绝，加密属 RuoYi 专有逻辑不进通用组件，因此 POST 三媒介与 basic/bearer 仅由属性面板占位模板承载，不提供可一键运行 mock。
- 验证：mvnw compile / oxlint / vue-tsc 静态校验；实测由用户进行（2xx 成功、4xx/5xx 抛错信息与容错开关、form/json/raw 三种体、超时、204 空体、3xx 跟随、query 多值、响应头白名单）。
