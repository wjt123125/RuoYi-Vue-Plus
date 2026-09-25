# 项目技术文档中心

本目录用于集中存放 RuoYi-Vue-Plus 项目的自研技术文档（区别于第三方框架的官方文档）。

## 目录规范

```
docs/
├── README.md          # 本文件：文档索引与存放规范
└── wiki/              # 技术文档（一篇文档讲透一个主题）
    ├── databus-el-express-parser.md       # EL 表达式与画布 JSON 双向转换引擎
    ├── databus-canvas-gateway-migration.md  # 画布渲染层：物理容器 → 网关节点范式迁移设计
    ├── databus-context-design.md          # DatabusContext 执行上下文设计（JSONPath/混合路径/动态变量）
    ├── databus-bpm-connector-service-design.md  # BPM Connector 端点 Service 设计（10 端点 schema/基类/取舍）
    ├── databus-data-components.md         # 数据类组件登记本（setValue/fieldMap/dataPatch/response 契约与边界）
    ├── databus-http-component.md          # HTTP 请求组件完善化规格（单组件参数化/五动词/三媒介/出口/错误/超时/auth）
    ├── databus-preview-step-result.md     # 试运行步骤结果展示（人话摘要+数据明细抽屉，含 LiteFlow 2.16.1 升级）
    ├── liteflow-el-normalize-bug.md       # LiteFlow execute2RespWithEL 篡改字符串字面量缺陷（issue 草稿+项目绕行）
    ├── databus-file-component.md          # 附件两件：FILE_UPLOAD/FILE_DOWNLOAD 端点与组件规格（base64/本地摘要校验）
    ├── databus-script-component.md          # 脚本组件：script/booleanScript 两物料、SPI 引擎热插拔、一期 Groovy
    ├── databus-loop-component.md            # 循环/路由组件：forLoop/iteratorLoop/switchRoute 规格、$i 索引栈、SWITCH tag
    ├── databus-bpm-endpoint-auth.md         # BPM 端点鉴权：官方文档实证 openapi 网关，推荐 B 主线（C 兜底），待两项实测
    ├── databus-formula-engine-research.md   # 公式引擎调研：结论不建独立引擎，三层模型 + QLExpress4 内联表达式储备方案
    ├── databus-expression-syntax.md         # 参数表达式语法契约：`{{ }}` 唯一动态标记、类型保留规则、引擎三步演进
    ├── databus-context-concurrency.md       # 并发安全：WHEN 并行写共享树的竞争路径、读写锁方案、两个否决方案
    └── n8n-vs-databus-paradigm.md           # 范式对比：n8n 传送带 vs 本项目共享黑板、诚实竞争边界、对标产品地图
```

## 写新文档时请遵循

1. **一篇文档一个主题**：命名格式 `模块名-主题.md`（小写中划线，如 `databus-el-express-parser.md`），放在 `wiki/` 下。
2. **写完必须登记**：在下方的"文档索引"表里加一行，方便后来人检索。
3. **内容建议包含**：这个模块解决什么问题、整体架构图/流程、核心概念解释、关键代码位置索引（类名即可）、常见坑与扩展指南。
4. **与代码同步维护**：模块结构性调整时同步更新对应文档，文档失效比没有文档更糟糕。

## 文档索引

| 文档 | 主题 | 模块 | 更新日期 |
| --- | --- | --- | --- |
| [wiki/databus-el-express-parser.md](wiki/databus-el-express-parser.md) | LiteFlow EL 表达式与画布 JSON 双向转换引擎 | ruoyi-databus (`org.dromara.databus.el`) | 2026-09-13 |
| [wiki/databus-canvas-gateway-migration.md](wiki/databus-canvas-gateway-migration.md) | 画布渲染层从物理容器范式迁移到网关节点范式（平级 nodes+语义边） | plus-ui `views/databus/editor` | 2026-09-15 |
| [wiki/databus-context-design.md](wiki/databus-context-design.md) | DatabusContext 执行上下文设计（JSONPath 读写/混合路径/动态变量机制与决策） | ruoyi-databus (`org.dromara.databus.context`) | 2026-09-16 |
| [wiki/databus-bpm-connector-service-design.md](wiki/databus-bpm-connector-service-design.md) | BPM Connector 端点 Service 设计（10 端点入参/出参 schema、公共基类、老系统取舍、原子化原则，含 RDS_EXECUTE/IDCARD_TO_USERID） | 1D-P0 BPM 端总线 app + ruoyi-databus | 2026-09-19 |
| [wiki/databus-data-components.md](wiki/databus-data-components.md) | 数据类组件登记本：setValue/fieldMap/dataPatch/response 契约、职责边界与旧 Doc*Processor 迁移映射 | ruoyi-databus (`org.dromara.databus.component.data`) | 2026-09-18 |
| [wiki/databus-http-component.md](wiki/databus-http-component.md) | HTTP 请求组件完善化规格：单组件参数化粒度决策、五动词+三媒介、响应出口/错误容错/超时/轻量 auth 与推后清单 | ruoyi-databus (`org.dromara.databus.component.protocol`) | 2026-09-19 |
| [wiki/liteflow-el-normalize-bug.md](wiki/liteflow-el-normalize-bug.md) | LiteFlow execute2RespWithEL 经 ElRegexUtil.normalize 篡改 data/tag 字符串字面量（删空格、单引号转双引号）：根因、复现、修复建议与项目绕行方案 | LiteFlow 2.16.x 第三方缺陷（issue 草稿） | 2026-09-19 |
| [wiki/databus-preview-step-result.md](wiki/databus-preview-step-result.md) | 试运行步骤结果展示：组件人话摘要进表格、名下 JSON 快照进抽屉明细；2.16.1 全局节点监听器采集、NodeStep VO 扩展、前端末列改造与升级注意 | ruoyi-databus + plus-ui `views/databus/editor` | 2026-09-19 |
| [wiki/databus-file-component.md](wiki/databus-file-component.md) | 附件两件：FILE_UPLOAD/FILE_DOWNLOAD 端点与组件契约（base64 形态、逐文件本地摘要校验、旧 processor 反向命名映射） | BPM 端总线 app + ruoyi-databus + plus-ui | 2026-09-20 |
| [wiki/databus-script-component.md](wiki/databus-script-component.md) | 脚本组件：script/booleanScript 两物料、SPI 引擎探测热插拔、LiteFlowNodeBuilder 动态注册、一期 Groovy 与安全边界 | ruoyi-databus + plus-ui `views/databus/editor` | 2026-09-20 |
| [wiki/databus-loop-component.md](wiki/databus-loop-component.md) | 循环/路由组件：forLoop/iteratorLoop/switchRoute 三物料契约、$i/$j/$k ThreadLocal 索引栈、EL 条件位 tag/data 修复与 SWITCH 分支 tag、前端护栏与小表单、BREAK/DEFAULT/parallel 推后清单 | ruoyi-databus + plus-ui `views/databus/editor` | 2026-09-20 |
| [wiki/databus-bpm-endpoint-auth.md](wiki/databus-bpm-endpoint-auth.md) | BPM 12 端点鉴权：裸奔现状与 IP 白名单绕过、官方文档实证 /portal/openapi 签名网关（5 分钟窗、自定义 cmd 一等公民）、推荐 B 主线 C 兜底（待网关实测） | BPM 端总线 app + ruoyi-databus + plus-ui | 2026-09-20 |
| [wiki/databus-formula-engine-research.md](wiki/databus-formula-engine-research.md) | 公式引擎调研：旧 5 公式归宿、业界三层模型（结构化/内联表达式/脚本）、JVM 引擎横评、QLExpress4 已随 LiteFlow 在 classpath；结论当前不建引擎，给触发信号与储备设计 | ruoyi-databus（调研，无代码改动） | 2026-09-20 |
| [wiki/databus-expression-syntax.md](wiki/databus-expression-syntax.md) | 参数表达式语法契约：`{{ }}` 唯一动态标记、整字段保留原类型、PathResolver 三分支收敛（含裸子串误判缺陷）、QLExpress4 三步演进与 GraalJS 否决理由 | ruoyi-databus（规范，无代码改动） | 2026-09-24 |
| [wiki/databus-context-concurrency.md](wiki/databus-context-concurrency.md) | DatabusContext 并发安全：WHEN 并行写共享树的竞争路径（createPath 根 Map + handleArrayPath 活引用）、子树父键概念、ReentrantReadWriteLock 锁分配与不死锁论证、两个否决方案、getDocument 后门 | ruoyi-databus (`org.dromara.databus.context`) | 2026-09-24 |
| [wiki/n8n-vs-databus-paradigm.md](wiki/n8n-vs-databus-paradigm.md) | 两种数据范式：n8n 传送带（item 数组）vs 本项目共享黑板（全量树）、4 条真实链路逐项对照、双方盲区对称列举、诚实竞争边界（含两处已撤回论证）、对标产品地图 | 架构论证（无代码改动） | 2026-09-24 |
