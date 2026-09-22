# 脚本组件规格：script / booleanScript

> 阶段 1E 并行讨论产出（2026-09-20 拍板，代码未启动）。画布新增 2 个脚本物料，
> 承接长尾、一次性、不规则的值转换；语言引擎走 SPI 热插拔，一期只装 Groovy。

## 1. 定位：三层能力模型

> 2026-09-20 公式引擎调研后由「双层」升级为「三层」（决策档：[databus-formula-engine-research.md](databus-formula-engine-research.md)）。
> 平台能力分三层，不全做成 Java 组件、也不全脚本化：

| 层 | 适用 | 现状 | 参照 |
| --- | --- | --- | --- |
| Java 原子组件 | 高频、稳定、需要明确参数契约的能力（fileUpload、rdsExecute 等） | 已建成，condition 含 10 个结构化操作符 | LiteFlow 官方：固定逻辑用 Java 组件 |
| 参数槽内联表达式 | 一行算术/三元/字符串函数/复合条件（如 `= $.amount * 0.8`） | **缺口，当前不实施**；储备方案 QLExpress4（已随 liteflow-core 在 classpath）`=` 前缀，触发信号与设计见调研档 §6 | n8n 表达式、Node-RED JSONata、Camunda JUEL |
| 脚本节点 | 长尾、一次性、不规则的值转换、多行逻辑 | 本期落地（Groovy） | n8n Code 节点、Zapier Code by Zapier |

中间层未实施前，"一行计算也要拖脚本节点"是有意接受的摩擦：表达式语法一旦发布即对外契约，
等真实需求样本（旧 meta 公式频度摸底 / 用户配置摩擦实证）出现再补，晚定比早定便宜。

拍板背景：旧系统 `BOUtil` 的纯本地 convert 中还有 3 个在新系统无对应——JSON 对象转字符串
（convertJsonObjectToJsonString）、时间戳转日期、日期串按格式转日期。**这 3 个不出组件**，
由脚本节点承接；旧 fieldConfig 迁移时，冷门 convert 直接翻译成脚本节点而非永久转换器。
旧系统 `@公式` 不做 1:1 迁移（其执行器是 BPM 平台 RuleAPI，不是项目资产），5 个自定义公式的
逐个归宿见调研档 §2。

## 2. 物料（一期 2 个）

物料按**节点用途**分；语言是节点属性，不按语言出物料：

| 物料 type | 名称 | LiteFlow 节点 type | 用途 | icon |
| --- | --- | --- | --- | --- |
| `script` | 脚本 | `script` | THEN 链中的普通处理节点 | `ph:code` |
| `booleanScript` | 条件脚本 | `boolean_script` | 可拖入 IF 条件槽，脚本必须返回 true/false | `ph:terminal-window` |

选择脚本（switch_script）、循环脚本（for_script）随 SWITCH/FOR 循环迭代再补，不预防性做。

## 3. 节点配置

```json
{
  "language": "groovy",
  "script": "def v = databusContext.read('$.request.code'); databusContext.save('$.script1.out', v)"
}
```

- `language`：必填，缺省 `groovy`，取值必须在当前已安装引擎清单内。
- `script`：必填，脚本文本。
- 脚本节点产出由脚本自行决定写到数据空间何处；节点 tag 仅作节点标识与标题来源，不强制产出路径。

## 4. 语言引擎：SPI 热插拔

LiteFlow 引擎经 Java SPI 发现：每个语言插件 jar 含
`META-INF/services/com.yomahub.liteflow.script.ScriptExecutor`。

- **后端新增探测端点**：直接用 `ServiceLoader.load(ScriptExecutor.class)` 列举已安装引擎，
  读每个 executor 的 `scriptType()`（displayName）返回语言清单。不反射 LiteFlow 内部 Map
  （`ScriptExecutorFactory` 的 scriptExecutorMap 是私有字段，不依赖它）。
- 前端 language 下拉根据该清单动态生成：装了哪个引擎 jar 就出现哪个语言；
  未来物料市场发布语言包可热生效，与 connector 热插拔同一思路。
- **一期引擎 Groovy**：语法近 Java、可直接调用任意 Java/hutool 类；
  依赖 `com.yomahub:liteflow-script-groovy`，版本对齐 LiteFlow 主版本 2.16.1
  （注意本地 m2 缓存过旧版 2.12.4，不可用）。
- javax-pro（Java 脚本）未来若引入须用 2.16.1.1+（ThreadLocal 泄漏 #IK6XVN）。

## 5. 运行时节点注册

脚本内容无法像 Java 组件一样只写注册名，建链前先用动态构造 API 注册：

```java
// 普通脚本
LiteFlowNodeBuilder.createScriptNode()
        .setId(随机 nodeId)
        .setName("脚本")
        .setLanguage("groovy")
        .setScript(脚本文本)
        .build();

// 条件脚本
LiteFlowNodeBuilder.createScriptBooleanNode()
        .setId(随机 nodeId)
        .setLanguage("groovy")
        .setScript(脚本文本)
        .build();
```

EL 中只引用注册得到的随机 nodeId（配合 `.tag("数据空间名")`）。多个脚本节点各自注册、
nodeId 互不重复。

## 6. 脚本内可用绑定

- `databusContext`：自定义上下文类 `DatabusContext` 类名驼峰形式，直接调用其 read/save
  等方法读写数据空间。
- `_meta`：slotIndex / currChainId / nodeId / tag / cmpData / loopIndex / requestData 等；
  `_meta.cmp` 相当于当前组件对象。
- 规则细节随 LiteFlow 官方脚本文档（各语言差异：Lua 用 `:`、Aviator 用 `method(bean,args)`、
  Kotlin 经 bindings）。

## 7. 安全边界（重要）

- 脚本节点 = 在 JVM 内执行任意代码。Groovy 引擎无沙箱，可接触整个 JVM 与 classpath。
- 当前可信内网用户场景可接受；**物料市场/对外开放前必须决策**：GraalVM polyglot 沙箱
  （graaljs 可限制资源/类访问）或对外部租户禁用脚本物料。
- 脚本编译/执行错误需翻译为节点级失败并展示引擎报错（脚本行号信息），不吞异常。

## 8. 实施清单（启动时按序）

- RuoYi：pom 加 liteflow-script-groovy（对齐 2.16.1）；新增脚本引擎探测端点（ServiceLoader）；
  执行器建链路径按节点类型注册脚本节点（script / boolean_script）
- plus-ui：cmp-defs +2 物料；CmpProps 脚本编辑（language 下拉取探测端点 + script 多行编辑）；
  CmpProps 配置占位；建议 1 个脚本 mock 示例
- 静态校验后交用户实测：Groovy 读写数据空间、条件脚本进 IF、错误脚本报错信息、
  language 清单随引擎安装变化
