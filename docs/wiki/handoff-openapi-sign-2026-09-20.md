# 交接：/portal/openapi 签名不通过排查（2026-09-20）

## 0. 一句话结论

**方案没选错、签名算法和发包方式经字节码逐指令核对与平台官方客户端完全一致**；报错来自平台服务端重算签名不一致，根因高度集中在两点：**CC 身份策略里的密码与 RuoYi 侧 apiSecret 不一致**（最可能），或**body 参数在服务端被取成了整段 form 串**。下一步只需两个低成本实测即可二分定位（见 §4），**不需要再翻 jar**。

## 1. 现象

- 链路试运行：节点 sessionCreate1（SESSION_CREATE）失败，耗时 104ms，平台返回：`签名不正确，请核对签名算法`
- 该文案精确匹配服务端 `com.actionsoft.bpms.api.Utils.validateRequest` 中 makeSig 比对失败分支（已解密字节码确认），说明请求已到达 /portal/openapi 且 access_key 被找到、对应凭证密码非空，仅仅是**重算的 sig 与请求 sig 不等**。

## 2. 已确认事实（证据链，全部来自平台 jar 字节码，非文档推测）

反编译产物留在：
- `.trae/tmp_validate.txt`（Utils 验签）
- `.trae/tmp_apiutils.txt`（makeSig/encryptHMAC/buildQuery）
- `.trae/tmp_oac.txt`（官方客户端 OpenApiClient）
- `.trae/tmp_param.txt`（ParameterHelper 参数解析）
- `.trae/tmp_disp_req.txt`（DispatcherRequest 调用验签点）
- `.trae/decrypt/`（Allatori 字符串解密一次性工具 + out.txt 明文对照）

### 2.1 协议确认

用户给的官方页 https://docs.awspaas.com/reference-guide/aws-paas-api-guide/appendix/http_vs_soap.html
就是 **Web API（/openapi）方案**：公共参数 timestamp / sig_method=HmacMD5 / cmd / access_key / format=json / sig，认证=access_key+secret 签名摘要。我们实现的正是这套，**方向正确**。

### 2.2 官方客户端 OpenApiClient.exec() 与我们的 BpmOpenApiSigner 逐行一致

1. 参数 map 放业务参数（Map/DTO 序列化成 JSON 字符串值）+ timestamp(ms)+cmd+format+access_key+sig_method=HmacMD5
2. `sig = makeSig(map, secret, ignoreList)`，再 put sig
3. body = `buildQuery`（k=v 用 URLEncoder UTF-8 编码，& 连接）
4. POST，Content-Type `application/x-www-form-urlencoded;charset=UTF-8`

我们 [BpmOpenApiSigner.java](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/connector/bpm/BpmOpenApiSigner.java) 与上面**完全相同**（TreeMap ASCII 升序、剔空值、secret 前缀、HmacMD5 大写 hex）。→ 算法/编码/传输层排除。

### 2.3 服务端验签做了什么（Utils.validateRequest）

1. 复制 Exchange.parameters
2. 剔除静态数组 `["responseType", "clientIp", "serverName"]`，再剔除 `sig`
3. 取 access_key；从 **CC 缓存 `CCCache.getListByType("sysidentity")`** 中找 `id == access_key` 的策略，取其 **`id_pwd` 字段作为 secret**（CC 身份模型的字段名经解密为 `id` / `id_pwd`，不是 access_key/secret）
   - access_key 找不到 → 另一种报错（"未找到[access_key]…[访问凭证]未提供"）
   - id_pwd 为空 → 另一种鉴权报错
   - 都不是我们看到的文案 → **access_key=databus 存在且 id_pwd 非空，但值与 RuoYi 的 apiSecret(databus) 对不上**
4. makeSig(剩余参数, id_pwd, cmd 的 ignoreSign 集合) 与请求 sig 做 String.equals；不等 → 正是当前报错
5. 之后才校验 timestamp：必须含该参数，默认窗口 **360000ms（6 分钟，不是 5）**，可用平台属性 `api.overtime`（分钟）覆盖；超时文案是"请求超时"，与当前不符 → 时钟问题排除

### 2.4 唯一剩余的代码侧变量：body 如何被取进 Exchange.parameters

ParameterHelper.parameterMatch 两条分支：
- **cmd 已注册**（在 WebActions 注册表且带 WebParamObject 列表）：按声明参数逐个取值；其中 `type=="body"` 的参数会 **`request.getInputStream()` 读整段原始请求体**作为该参数值（不是 getParameter("body")）。若我们的 body 被当成这种类型，服务端参与验签的 body 值=整串 form 文本，必然不等
- **cmd 未注册但 cmd 含 "."**（我们 cmd=`com.awspaas.databus.connector.SESSION_CREATE`，含点）：把 servlet 容器解析出的**全部 form 字段**拷进 map（body 是正常单字段 URL 解码值）——与官方客户端口径一致

OPENAPI 注解 Controller 走哪条分支，取决于注解扫描注册时给 @Param 生成的 WebParamObject 类型；已看到 `AppCmd.getIgnoreSign()` 会读取 `@Param.ignoreSign()` 注解属性（**@Param 自带 ignoreSign 开关**，见 tmp_appcmd.txt 356-390 行），但注册类型的最终确认还没做完（下一个类应看 AWSWebServerScanner 或 AppCmd 注解→WebActionObject 的转换）。

## 3. 根因排序

1. **【最可能】CC 身份策略 databus 的 id_pwd 不是 "databus"**：建策略时输错过、有空格、或改过没保存/缓存没刷新。access_key 能匹配上但密码不一致，现象与当前报错完全吻合。
2. **【次可能】SESSION_CREATE 的 body 在服务端走了"原始流"分支**：PING（无 body）若能过、只有带 body 的 cmd 不过即坐实。
3. 算法、URL 编码、时间窗、传输格式：已排除（§2.2/2.3）。

## 4. 接手后按此顺序做（两步实测，零改码）

### 步骤 A：PING 二分（先做，最便宜）

RuoYi 连接管理对 bpm-default 点「测试连接」（调 PING，无 body，仅公共 5 参数）：
- **PING 也报签名不正确** → 100% 是 secret 不一致（无 body 可分歧）→ 走步骤 B
- **PING 成功、SESSION_CREATE 失败** → body 取参分歧 → 走步骤 C
- 若 PING 报"未找到 access_key/访问凭证" → CC 身份策略没绑到该 cmd 或策略未发布，先在 CC「发布 > HTTP API」确认 13 个 cmd 全部绑定 databus 身份

### 步骤 B：对齐 CC 密码

1. CC 控制台 → 身份策略（sysidentity）→ 打开 databus 那条，**把密码重新输入一遍 `databus`**（注意首尾空格），保存
2. 必要时重新「发布 > HTTP API」全部 cmd 使缓存刷新；CC 缓存一般无需重启 BPM
3. 再点测试连接。通过后把 RuoYi 侧 apiSecret 同步改成 CC 最终值（或反之，两边一致即可）
4. 上线前按既定计划轮换强随机值（SQL 种子 databus/databus 仅本地开发）

### 步骤 C：body 分支坐实后的修法（未实施，留给接手人判断）

- 先确认 OPENAPI 注解 cmd 的参数注册类型：反编译 `com.actionsoft.bpms.server.AWSWebServerScanner`（bin/lib aws-infrastructure-*.jar 内，搜 AppCmd 注册/WebActionObject 构造）或直接在 BPM 开 debug：平台属性 AWSWebDebug=on 时 ParameterHelper 会打印 cmd 注册日志（`cmd [*]`），可看 cmd 走哪条分支
- 若 body 被注册成 type=body（原始流）：与平台所有标准 openapi API 用 form 单字段传参的惯例矛盾，优先怀疑我们 @Param 用法。候选修法：
  - 给 body 参数加 `@Param(value="body", required=true, ignoreSign=true)`？——**不建议直接用**，会让 body 脱离验签（平台 openapi 业务参数本应参与签名），仅作验证手段
  - 参考平台 SDK 自带 OPENAPI Controller（如 com.actionsoft.sdk.service.* API 实现类，aws-sdk-local.jar）里 String 入参的标准注解写法，对齐我们的 Controller 方法签名
- 也可写个 main 直接用平台 `OpenApiClient.exec("com.awspaas.databus.connector.PING", map)`（构造函数 serverUrl/accessKey/secret）打 PING：官方客户端能过、我们的不能过=我们发串问题；官方客户端也不过=CC secret 问题。这是最强的旁证工具

## 5. 本次未改动任何业务代码

本轮只做了只读逆向 + `.trae/` 下临时分析文件。上一轮全量切 openapi 的业务代码保持已编译状态（BPM class 在 bin/production/release，javac 1.8 通过）。
`.trae/decrypt/`（Decrypt.java/.class/out.txt）与 `.trae/tmp_*.txt` 为一次性逆向产物，问题解决后可整目录删除。

## 6. 环境速查

- javap/javac：`e:\01.code\Actionsoft\release\jdk1.8\bin\`
- 平台 jar：`e:\01.code\Actionsoft\release\bin\lib\aws-*.jar`（验签在 aws-infrastructure-core.jar 的 com.actionsoft.bpms.api.Utils；makeSig 在 aws-api-client.jar / aws-infrastructure-api.jar 的 ApiUtils；官方客户端 OpenApiClient 在 aws-api-client.jar）
- web 层：`webserver\webapps\portal\WEB-INF\lib\aws-infrastructure-web.jar`（DispatcherOpenAPIServlet / ParameterHelper / WebActionScanner）
- 反编译命令模板（PowerShell，cp 用 `;` 分隔）：
  `& "<jdk1.8>\bin\javap.exe" -p -c -classpath <cp> <类名>`
- Allatori 字符串：两个解密器（NodeModel.ALLATORI_DEMO 与 ProxyManual.ALLATORI_DEMO），同密文两器结果不同，`.trae/decrypt/Decrypt.java` 已封装常量池自动解密
