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
    └── liteflow-el-normalize-bug.md       # LiteFlow execute2RespWithEL 篡改字符串字面量缺陷（issue 草稿+项目绕行方案）
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
| [wiki/liteflow-el-normalize-bug.md](wiki/liteflow-el-normalize-bug.md) | LiteFlow execute2RespWithEL 经 ElRegexUtil.normalize 篡改 data/tag 字符串字面量（删空格、单引号转双引号）：根因、复现、修复建议与项目绕行方案 | LiteFlow 2.16.x 第三方缺陷（issue 草稿） | 2026-09-19 |
