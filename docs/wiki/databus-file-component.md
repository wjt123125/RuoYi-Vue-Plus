# 附件两件规格：FILE_UPLOAD / FILE_DOWNLOAD

> 阶段 1E 并行支线（2026-09-20 开启）。BPM 端总线 app 新增 2 个端点（端点数 10 → 12），
> ruoyi-databus 新增 2 个业务组件，plus-ui 新增 2 个物料与 1 个 mock 示例。

## 1. 背景：旧系统行为与命名陷阱

旧总线 `com.awspaas.user.apps.data.bus` 有两个附件 processor，**命名方向与新端点恰好相反**，迁移时注意：

| 旧 processor | 实际语义 | 旧 SDK 调用 |
| --- | --- | --- |
| `FileToBase64Processor` | **下载**：读 BO 记录附件字段上的全部文件，内容转 base64 写回 document | `SDK.getBOAPI().getFiles(boId, fieldName)` + `getFile(fileId)` |
| `Base64ToFileProcessor` | **上传**：读 document 中 List\<FileDTO\>（base64 内容），可选摘要校验后上传到 BO 记录附件字段 | `SDK.getBOAPI().upFile(formFile, inputStream)` |

旧 `FileDTO` 字段：`id / fileName / securityLevel(Integer) / fileSize(long) / createUser / fileContent(base64)`。

旧系统两个不合理点，新设计规避：

1. 旧上传的校验配置（`validate / checkMethod / checksum`）是**全局单值**——对文件列表只能校验同一个期望值，多文件场景形同虚设。新设计改为**逐文件携带**校验信息。
2. 旧下载读取单个文件内容失败时只 `log.error` 后继续（fileContent 为 null 静默出参）。新端点不吞异常，单文件读取失败即端点失败。

## 2. 架构归属

遵循 1D-P0 既定分层：

- **BPM 端 app**（`com.awspaas.databus.connector`，独立仓库）：两个原子端点，入参完全自包含，不读任何共享上下文；只负责 base64 ↔ BPM 附件存储的转换。
- **Connector 层**（ruoyi-databus `BpmHttpConnector`）：+2 操作方法，HTTP 调用与错误翻译，无业务逻辑。
- **Component 层**（ruoyi-databus 两个组件）：从数据空间解析 FileDTO 列表/BO 定位参数；**md5/sha1/sha256/sha512 摘要校验在组件侧本地完成**（不经过 BPM 网络调用），校验通过才发上传请求。

### 2.1 打包形态决策（2026-09-20 拍板）

讨论起点：旧系统文件内嵌在 BO payload 的字段位置，靠 fieldConfig 的 convert/type 标记特殊处理；是否复刻？

拍板结论：

1. **分离式是唯一原生形态**：文件数据独立于 BO 入参存放（如 `$.request.files`），fileUpload 用 sourcePath（任意 JSONPath）引用。不支持「文件内嵌在 boCreate 入参里」的写法。
2. **boCreate 只接收纯粹 BO 数据**，不内置任何字段类型转换；文件上传、身份证换 userId、SQL 等**带网络副作用的操作一律各自独立原子节点**（n8n / Zapier / Camunda 通用范式：显式编排、可逐节点观测成败、能力可跨场景复用）。
3. **纯本地、无副作用的值转换**（时间戳/日期串转日期、JSON 对象转字符串、alias）归 fieldMap/通用转换节点，不拆成一个操作一个节点（参照 n8n Set、Zapier Formatter）。
4. **旧 fieldConfig/convert 体系不复刻、不内嵌进组件**；旧调用方（只发 apiId + data）请求契约不变，存量 meta 在迁移时经**适配层一次性翻译**成上述原生节点组合，按 apiId 逐个迁移、逐个验收，旧 app 并行运行至对应接口下线（绞杀者模式）。
5. 明确不做旧 `convertBase64ToFile` 的「建 BO 时先写文件名字符串」hack：新 SDK upFile 自建附件关联，手工文件名串多余且依赖平台存储细节。

## 3. 端点契约

### 3.1 FILE_UPLOAD（base64 → BPM 附件）

请求：

```json
{
  "boId": "1234567890",
  "appId": "com.awspaas.user.apps.demo",
  "boName": "BO_DEMO_MAIN",
  "boItemName": "BO_FIELD_FILE",
  "processInstId": "",
  "taskInstId": "",
  "files": [
    {
      "fileName": "demo.txt",
      "fileContent": "aGVsbG8=",
      "securityLevel": 1,
      "fileSize": 5
    }
  ]
}
```

- `boId / appId / boName / boItemName / files` 必填；`files` 至少 1 个元素，每项 `fileName / fileContent` 必填。
- `processInstId / taskInstId` 可选（旧系统经 FormFileModel 透传，保持附件与流程实例的关联）。
- `securityLevel` 可选，缺省 0；`fileSize` 可选，**以解码后实际字节数为准回填**，不信任入参声明。

处理流程（逐文件，顺序上传）：

1. hutool `Base64Decoder.decode(fileContent)` 解码；base64 非法 → 端点失败。
2. 构造 `FormFileModel`：设置 appId / boName / boItemName / boId / processInstId / taskInstId / fileName / securityLevel，`fileSize` 设为实际字节数。
3. `SDK.getBOAPI().upFile(formFile, inputStream)`。
4. 收集上传结果项：`{fileName, fileSize}`；SDK 在 model 上回填了 id 时附带 `id`（不保证有，调用方不得依赖）。

响应 data：

```json
{
  "uploadedCount": 1,
  "files": [{"fileName": "demo.txt", "fileSize": 5}]
}
```

### 3.2 FILE_DOWNLOAD（BPM 附件 → base64）

请求：

```json
{
  "boId": "1234567890",
  "fieldName": "BO_FIELD_FILE"
}
```

- `boId / fieldName` 必填。

处理流程：

1. `List<FormFile> formFiles = SDK.getBOAPI().getFiles(boId, fieldName)`。
2. 逐个 `getFile(formFile.getId())` 读字节流 → hutool `Base64Encoder.encode`。
3. 任一个文件读取抛异常 → 不吞，端点失败（BPM_FILE_DOWNLOAD_FAILED）。

响应 data：

```json
{
  "fileCount": 1,
  "files": [
    {
      "id": "附件ID",
      "fileName": "demo.txt",
      "securityLevel": 1,
      "fileSize": 5,
      "createUser": "admin",
      "fileContent": "aGVsbG8="
    }
  ]
}
```

附件字段无文件时返回 `{fileCount: 0, files: []}`，属正常结果不报错。

## 4. 组件契约

### 4.1 fileUpload（注册名 `fileUpload`）

节点参数：

```json
{
  "connectionId": "bpm-default",
  "sourcePath": "$.request.files",
  "boId": "$.boCreate1.boId",
  "appId": "com.awspaas.user.apps.demo",
  "boName": "BO_DEMO_MAIN",
  "boItemName": "BO_FIELD_FILE",
  "processInstId": "$.processStart1.processInstanceId",
  "taskInstId": "",
  "validateChecksum": true
}
```

- `connectionId / sourcePath / boId / appId / boName / boItemName` 必填；字符串参数走统一参数解析（裸路径 / 混合模板 / 字面量）。
- `sourcePath` 指向数据空间中的文件数组，每项：`fileName`、`fileContent`（base64）必填，`securityLevel / fileSize` 可选，**可选 `checkMethod`（md5/sha1/sha256/sha512）+ `checksum`（期望摘要，十六进制）**。
- 本地摘要校验：
  - 某项携带 `checkMethod + checksum` 时始终校验，不匹配立即失败，不发请求；
  - `validateChecksum=true`（缺省 false）时要求每个文件都携带校验信息，缺失即失败；
  - `checkMethod` 取值非法即失败。
- 产出存 `$.<tag>`：`uploadedCount`、`files`（BPM 回传的结果项）。
- 步骤摘要：`上传 N 个附件`（N 为数量增量信息）。

### 4.2 fileDownload（注册名 `fileDownload`）

节点参数：

```json
{
  "connectionId": "bpm-default",
  "boId": "$.boCreate1.boId",
  "fieldName": "BO_FIELD_FILE"
}
```

- 三字段均必填；`boId` 走参数解析，`fieldName` 为字面量字段名。
- 产出存 `$.<tag>`：`fileCount`、`files`（含 base64 的完整 FileDTO 列表，可直接作为 fileUpload 的 `sourcePath` 输入——附件搬运链无需中间转换）。
- 步骤摘要：`下载 N 个附件`；N=0 时报 `下载 0 个附件（字段无文件）`。

## 5. 错误码（BpmErrorCode 追加）

| 错误码 | 场景 |
| --- | --- |
| `BPM_FILE_UPLOAD_FAILED` | FILE_UPLOAD：base64 解码失败或 upFile 抛异常 |
| `BPM_FILE_DOWNLOAD_FAILED` | FILE_DOWNLOAD：getFiles / getFile 抛异常 |

参数缺失统一走 `BPM_PARAM_MISSING`；未预期异常走 `BPM_INTERNAL_ERROR`。

## 6. 范围外（本档不做）

- 附件删除（SDK 已有 `removeFile / removeFiles`，后续按需要补端点与组件）。
- 云存储/云文件信息（FormFileModel 的 cloudInfo / groupName 等）端点不暴露。
- 大文件分片/流式直传：base64 方案有 ~33% 膨胀，超大文件场景待鉴权与物料市场阶段另评估。
- 12 个端点的鉴权仍为 `session=false`，上线前随鉴权方案统一补齐。

## 7. 文件索引

**BPM 端 app（独立仓库 `com.awspaas.databus.connector`）**

- 新增 `dto/FileUploadRequest.java`（内含 FileItem）、`dto/FileDownloadRequest.java`
- 新增 `service/FileUploadService.java`、`service/FileDownloadService.java`
- 修改 `controller/DataBusConnectorController.java`（+2 端点）、`config/action.xml`（+2 cmd-bean）
- 修改 `exception/BpmErrorCode.java`（+2 错误码）

**ruoyi-databus**

- 新增 `connector/bpm/dto/FileUploadRequest.java`（+FileUploadItem）、`connector/bpm/dto/FileDownloadRequest.java`
- 修改 `connector/bpm/BpmConst.java`、`connector/bpm/BpmHttpConnector.java`
- 新增 `component/bpm/FileUploadCfg.java`、`component/bpm/FileUploadComponent.java`
- 新增 `component/bpm/FileDownloadCfg.java`、`component/bpm/FileDownloadComponent.java`

**plus-ui**

- 修改 `editor/cmp-defs.ts`（+2 物料，icon：ph:upload-simple / ph:download-simple）
- 修改 `editor/components/CmpProps.vue`（+2 配置占位）；附件演示并入「BPM 全链路」示例（boCreate 建记录 → fileUpload → fileDownload 读回 → taskComplete），不单独开示例

**部署副本同步（mldataboard 工作区，仅文件同步不入库）**

- `bin/production/release/com/awspaas/databus/connector/config/action.xml`
- `webserver/webapps/portal/apps/com.awspaas.user.apps.databus.connector/action.xml`
- `apps/install/com.awspaas.user.apps.databus.connector/web/com.awspaas.user.apps.databus.connector/action.xml`
