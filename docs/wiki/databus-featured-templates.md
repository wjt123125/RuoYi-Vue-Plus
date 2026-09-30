# 精选模板设计档（链路模板库·近期层）

> 状态：设计定稿待评审（2026-09-30）
> 关联：roadmap《next.md 12 条讨论》模板策略（两层）；复用既有「复制链路」能力（2026-09-24 收线）
> 范围：ruoyi-databus 后端 + plus-ui 链路管理页，无 BPM 端改动

## 1. 背景与目标

链路管理页当前的新建入口是「新增链路 → 空白画布」。对业务人员来说，知道有哪些组件、组件间怎么连、入参登记怎么写，门槛都在第一条链路上。而 27 条种子链路里已经沉淀了覆盖核心范式的优质样板（免登录 HTTP、IF/WHEN/FOR/ITERATOR/SWITCH/CATCH、Groovy 脚本、BPM 全链路），却和普通草稿混在同一个列表里，没有露出、没有说明、没有一键取用的路径。

**精选模板＝给优质链路打标记，在链路管理页内开一个「精选模板」tab 集中露出，用户一键复制生成自己的草稿再改。** 这是 roadmap 模板两层策略里的近期层：

- 近期层（本档）：精选模板＝链路上的一个标记 + 复制即用，**不建模板市场系统**。
- 正式层（后置）：模板市场随物料市场阶段（schema-driven、组件元数据后端化）一起做。

解决的问题：

1. 冷启动：从「复制一个会跑的例子再改」开始，而不是面对空白画布。
2. 样板治理：优质链路可被运营性地挑选、说明、排序，劣质/占位链路不进模板库。
3. 零新机制：模板就是链路，编辑、试运行、复制、权限全部复用既有设施。

## 2. 做什么 / 不做什么

### 2.1 本期做

- `databus_chain` 增加三个模板字段（标记 + 说明 + 排序）。
- 链路管理页内增加「我的链路 / 精选模板」两个 tab，模板集中展示（说明 + 步骤拓扑）。
- 「使用模板」：复用复制逻辑生成草稿副本，成功后直接跳编辑器编排副本。
- 模板维护：有权限的角色可把链路「设为模板」（填说明、排序）、修改模板设置、「取消模板」。
- 模板红线：模板不允许发布（不推 Rule-DB、不进正式执行/手动执行入口）；已发布链路必须先下线才能设为模板。
- 首批 10 条种子模板直接写进 mock insert（从 27 条种子中挑选，附面向用户的说明文案）。
- 新增一个按钮权限点 `databus:editor:template`（标记/取消/改模板设置）。

### 2.2 本期不做（边界）

- **不建独立模板表、不建模板市场**：模板与链路同表，模板 id 即链路 id，无模板-副本关联表。
- **不做模板与副本的关联同步**：复制即独立，模板后续更新不影响任何已复制副本（与现有 copy 语义一致）。
- **不做分类、标签、评分、使用次数、截图、模板导入导出**：数量信号出现前不前置（分类未来一个字段即可补，见 §9）。
- **不做「新建向导选模板」**：模板库 tab 就是唯一入口，不做弹窗向导。
- **模板不发布、不进 Rule-DB、不产生执行记录**：模板的可运行性靠编辑器内试运行验证（试运行不要求已发布）。
- 不新增侧边栏菜单（tab 在链路管理页内）。

## 3. 数据模型

模板是 `databus_chain` 上的三个新列，不引入新表。**开发阶段 DDL 约束（2026-09-30 拍板）：不写 ALTER 迁移脚本，表结构变更直接改进 [databus_chain.sql](file:///e:/01.code/RuoYi-Vue-Plus/script/sql/databus_chain.sql) 的 `create table` 全量 DDL，开发库 drop 重建后重跑 SQL 与种子数据，历史数据允许删除。** 三列直接加在建表语句中（位于 `input_params` 之后、审计字段之前）：

```sql
create table databus_chain (
    ...
    input_params      text            default null               comment '链路入参登记表 JSON（ChainInputParam 列表：路径/类型/默认值/必填，2026-09-27 增）',
    is_template       char(1)         default '0'                comment '是否精选模板（0否 1是，2026-09-30 增）',
    template_desc     varchar(500)    default null               comment '模板说明（适用场景/前置条件，模板库卡片展示，标记模板时必填）',
    template_sort     int(11)         default 0                  comment '模板排序（升序，值小在前，默认 0）',
    create_dept       bigint(20)      default null               comment '创建部门',
    ...
) engine=innodb comment='数据总线链路定义表';
```

字段语义：

| 字段 | 用途 | 约束 |
| --- | --- | --- |
| `is_template` | 模板标记，两个 tab 的分流依据 | char(1)，'0'/'1'，默认 '0' |
| `template_desc` | 面向用户的模板说明（适用场景、前置条件、能学到什么），模板卡片主文案 | 标记模板时必填，≤500 字；取消模板时清空 |
| `template_sort` | 模板库内手动排序，升序 | 整数，默认 0，同值按 update_time desc |

设计取舍：

- **为什么不建独立模板表**：模板的全部内容（画布、组件树、入参登记、log_level）就是链路本身，独立表要么全量冗余、要么引入 join 和双写；标记方案只增 3 列，符合「不建模板市场系统」的拍板。
- **为什么需要 `template_desc` 而不直接展示 `remark`**：remark 是内部备注（种子数据里是「免授权免加密：JSON 抽取 + 纯文本响应」这种开发视角短语），模板说明要写给业务人员看（适用场景 + 前置条件），受众和语气不同。
- **为什么不加分类字段**：首批 10 条、总量预期几十条以内，分类是空架子；等模板多到需要分类时补 `template_category` 单列即可，无需迁移。
- **模板与状态的关系**：模板恒为草稿态（status='0'）。模板是「样板」不是生产资产——发布意味着推 Rule-DB、出现在手动执行下拉、进入执行审计，这些对样板都无意义。模板内容的可运行性由编辑器试运行保证（`previewRun` 不要求已发布）。

## 4. 后端设计

### 4.1 实体 / BO / VO

- [DatabusChain](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/domain/DatabusChain.java) 增加 `isTemplate` / `templateDesc` / `templateSort` 三字段（普通列，无 typeHandler）。
- `DatabusChainVo` 同步三字段（列表卡片与抽屉回显用）。
- `DatabusChainBo` 仅增加**查询用** `isTemplate` 字段（与现有 `status` 同口径：保存不接收）。模板的标记/取消走独立端点与专用 BO，不进通用保存接口：
  - 新增 `TemplateMarkBo`：`templateDesc`（@NotBlank，@Size(max=500)）、`templateSort`（可空，默认 0）。
- MapstructUtils 按同名拷贝对新字段天然生效，但模板字段不得经 insert/update 通路写入（见 4.3 红线）。

### 4.2 查询接口

`GET /databus/chain/list`（[DatabusChainServiceImpl.queryPageList](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/service/impl/DatabusChainServiceImpl.java)）：

- BO 增加 `isTemplate` 等值过滤；前端两个 tab 必传 `'0'` 或 `'1'`，服务端对 null 不追加条件（兼容 stats 等内部调用）。
- 列表白名单 select 追加三列（`template_desc` 仅模板卡片消费，但走同一查询，随白名单带出成本可忽略）。
- 排序分流：`isTemplate='1'` 时 `order by template_sort asc, update_time desc, id desc`；普通 tab 维持 `update_time desc, id desc`。

`GET /databus/chain/stats`（顶部计数）：三个计数统一追加 `is_template='0'`，模板不计入草稿/已发布/已下线/总数。

### 4.3 模板专用端点（独立权限、独立审计）

沿用项目「状态流转类动作独立端点」范式（与 publish/offline/copy 一致），而不是把标记塞进通用 PUT：

| 端点 | 语义 | 权限 |
| --- | --- | --- |
| `POST /databus/chain/template/{id}` | 设为模板（body：TemplateMarkBo） | `databus:editor:template` |
| `DELETE /databus/chain/template/{id}` | 取消模板 | `databus:editor:template` |

均加 `@RepeatSubmit` 与 `@Log`（BusinessType.UPDATE）。

Service 规则（`IDatabusChainService` 增 `markAsTemplate(Long id, TemplateMarkBo bo)` / `unmarkTemplate(Long id)`）：

- mark：
  1. 链路存在且未删除；
  2. **status 必须非已发布**——`status='1'` 抛「请先下线该链路再设为模板」；
  3. 置 `is_template='1'`、写说明与排序；
  4. 不触碰画布/组件树/EL/状态。
- unmark：置 `is_template='0'`，清空 `template_desc`、`template_sort` 归 0；不改变 status（模板恒草稿，取消后回到普通草稿）。
- 防御性红线：
  - `insertByBo` 强制新链路 `isTemplate='0'`（新建即模板的通路不开，先有链路再标记）。
  - `updateByBo` 不经手模板三字段（BO 保存通路无这三个字段，从根上杜绝普通编辑越权改标记）。
  - `publish` 检查 `is_template='1'` 直接抛「模板不可发布，请复制后发布副本」；`offline` 同理防御（模板理论上进不了已发布态）。
  - 删除链路：模板与普通链路同规则（`databus:editor:remove`），删除模板不影响任何已复制副本（本就无关联）。

### 4.4 复制端点改造 + 副本命名弹窗（2026-09-30 讨论修订）

#### 身份模型：编码终身不变，换码走「复制-替换」

- **chainCode = 链路终身身份**：任何状态（含草稿）创建后一律不可改，不做发布后改名/规则迁移。它是 lf_chain 主键、执行通道/CHAIN 引用/外部调用的寻址依据。
- 想换编码的唯一路径：复制出一条新编码链路 → 调整发布 → 删除原链路。删除已由 `deleteByIds` 幂等清理 Rule-DB 规则（`removeChainQuietly`），已发布链路同样适用；历史执行记录保留旧编码快照。
- ChainForm 编码禁用框提示语改为：「编码创建后不可修改；如需更换，请复制本链路并指定新编码，发布副本后删除原链路」。
- **chainName = 展示标签**：不推 Rule-DB（发布只推 chainId/EL/脚本），任何状态可随时在 ChainForm 修改；本期不做编辑器内就地改名。

#### 复制弹窗：名称与编码在创建那一刻定型

普通「复制」与「使用模板」**都先弹 ChainCopyDialog**（名称 + 编码，确认后才创建），取代后端静默生成 `源编码_copy_随机`（该默认已废止，乱码感重）：

- 新增建议值端点（如 `GET /databus/chain/{id}/copy-suggestion`）：名称建议 `源名称 + "副本"`；编码建议 **`源编码_2`、`_3` 递增**——在源编码基础上从 2 起查库取首个未占用值（不在已占用建议值上继续追加，避免 `xxx_2_2`）。用户可在弹窗内改写，提交时走与新增同款编码格式/唯一性校验。
- `POST /databus/chain/copy/{id}` 改为接收 ChainCopyBo（chainName、chainCode 均必填）；Service `copy(Long id, ChainCopyBo bo)` 返回 **`Long`（新链路 id）**，Controller 返回 `R<Long>`。
- copy 内显式保证：`add.setIsTemplate("0")`，不拷贝 `templateDesc` / `templateSort`（有意的模板剥离，非遗漏）。
- 其余复制语义不变：status=草稿、画布/组件树/入参登记/log_level 原样复制、连接器仅复制 connectionId 引用、草稿不推 Rule-DB、EL 由组件树实时生成。
- 普通「复制」确认后留在列表刷新；「使用模板」确认后消费返回 id 直接跳编辑器。

### 4.5 与执行通道的隔离（无需额外改动，依赖红线推导）

模板不进入执行体系，全部由「模板恒草稿、不可发布」这一条保证：

- 正式执行/重跑 Service 层本就校验 `status=1`：模板恒为 '0'，天然拦截。
- 手动执行弹窗的链路下拉查询条件是 `status=1`：天然不含模板。
- 模板不推 Rule-DB，`lf_chain` 中不存在模板编码。
- 模板验证可运行性的唯一通道：编辑器内**试运行**（previewRun 不查发布态、不落执行记录）。

## 5. 前端设计

### 5.1 页面结构（chain/index.vue）

在标题栏与搜索框之间增加一级大 tab，复用现有胶囊视觉：

```
[ 我的链路 ] [ 精选模板 ]
```

- `viewMode: 'mine' | 'template'`，默认 mine。切换时重置 `pageNum=1`、清空 status 并重新查询。
- 查询参数 `queryParams.isTemplate`：mine 传 '0'，template 传 '1'。
- mine tab：与现状完全一致（搜索 + 状态胶囊 + 卡片列表 + 分页 + 计数副标题）。
- template tab：
  - 隐藏状态胶囊（模板恒草稿，无状态可筛）；保留名称搜索与分页。
  - 副标题改为模板引导文案，如「复制一个会跑的样板开始，改造成你的链路」。
  - 空态：「暂无精选模板」。
- 不新增路由、不新增菜单；从模板复制出的副本始终落在 mine tab。

### 5.2 模板卡片（ChainCard.vue 增加 variant）

不新建组件文件，给 [ChainCard.vue](file:///e:/01.code/plus-ui/src/views/databus/chain/ChainCard.vue) 增加 `variant: 'chain' | 'template'`（默认 chain），两套卡片共用图标映射与迷你拓扑（模板恰恰最需要展示「包含哪些步骤」）：

| 区域 | chain（现状） | template |
| --- | --- | --- |
| 右上角 | 状态彩色圆点 | 「精选」角标（Star 图标 + 文字） |
| meta 行 | logLevel 胶囊 + 拓扑 + 时间 | **templateDesc 说明文案**（两行截断）+ 拓扑；隐藏 logLevel/时间 |
| 卡片主体点击 | 编排 | **使用模板**（主行动，不区分权限） |
| 底部操作 | 执行/发布/下线/编辑/复制/删除 | **使用模板**（primary，`databus:editor:add`）、编排模板（edit）、编辑（edit，开 ChainForm 改名称等）、删除（remove） |

模板态不出现发布/下线/执行/普通复制按钮。新增 emit `use-template`。

「使用模板」交互（index.vue `handleUseTemplate`）：

1. 先拉建议值（`GET /copy-suggestion`）打开 ChainCopyDialog，预填「xxx副本」名称与 `源编码_2` 递增编码，用户可改写；
2. 确认后调 `copyChain(id, bo)`，取返回的新副本 id；
3. `ElMessage.success('已创建副本「{新名称}」，开始编排吧')`，直接 `router.push` 编辑器（query.id = 新 id）；
4. 普通「复制」按钮共用同一弹窗，确认后留列表刷新（弹窗来源区分是否跳编辑器）。

### 5.3 模板维护入口（ChainForm + 标记弹窗）

模板属性属于运营动作，入口收在链路编辑抽屉 [ChainForm.vue](file:///e:/01.code/plus-ui/src/views/databus/chain/ChainForm.vue)，整体受 `v-hasPermi="['databus:editor:template']"` 控制，无权限者完全不可见；新增态（无 id）不显示：

- 当前非模板：底部一块「精选模板」区，按钮「设为精选模板」→ 打开 `TemplateMarkDialog.vue`（新文件，小弹窗）：
  - 模板说明 textarea（必填，≤500 字，placeholder 引导写「适用场景 / 前置条件 / 能学到什么」）；
  - 排序数字（默认 0，提示「值越小越靠前」）；
  - 提交调 `POST /template/{id}`，成功刷新。
- 当前已是模板：展示当前说明与排序，两个操作——「保存模板设置」（再调 mark 端点覆盖）、「取消模板」（`DELETE /template/{id}`，二次确认，提示「取消后历史副本不受影响」）。

API 封装（`src/api/databus/chain/`）：

- types：`DatabusChainVo` 增三字段；`DatabusChainQuery` 增 `isTemplate`；新增 `TemplateMarkBo` 类型；`copyChain` 返回类型改为 `R<number>`。
- index：`markTemplate(id, data)` / `unmarkTemplate(id)` 两个方法。

### 5.4 权限菜单 SQL

```sql
-- 挂「链路管理」(1762000000000000020) 下，F 按钮权限；非 admin 需角色勾选
insert into sys_menu values
  (1762000000000000027, '链路模板标记', 1762000000000000020, 7, '', '', '', 'N', 'Y', 'F', '0', '0',
   'databus:editor:template', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '链路设为/取消精选模板（运营动作）');
```

权限矩阵：

| 角色能力 | 权限点 |
| --- | --- |
| 浏览两个 tab、查看模板 | `databus:editor:list`（模板库不另设查询权限，复用 list） |
| 使用模板（复制出自己的草稿） | `databus:editor:add`（复制本质是新建） |
| 编排/修改模板内容 | `databus:editor:edit` |
| 设为/取消模板、改说明排序 | `databus:editor:template` |
| 删除模板 | `databus:editor:remove` |

## 6. 首批种子模板

不另建 UPDATE 种子文件，**直接修改 [databus_chain_mock_data.sql](file:///e:/01.code/RuoYi-Vue-Plus/script/sql/databus_chain_mock_data.sql) 中入选的 10 条 insert**：列清单追加 `is_template, template_desc, template_sort` 三列，插入时即为模板态（该文件头部"由 tools 脚本生成，请勿手工编辑"的注释已过时——mock-presets.ts 与生成脚本均已删除，mock SQL 现为手工维护，实施时同步更新文件头注释）。非模板行不列三字段、走默认值，与现有部分行不列 `input_params` 的处理方式一致。

挑选标准：① 优先纯本地、零外部依赖、导入即可试运行；② 覆盖核心编排范式（分支/并行/循环/容错/脚本/HTTP/集成）；③ 剔除「结构展示」占位链（空槽、纯 AND/OR/NOT、CHAIN 引用、嵌套结构演示）与高度环境特异的业务验证链。

| sort | chain_code | 模板说明（template_desc） |
| --- | --- | --- |
| 10 | http-anonymous-chain | 免登录、无外部依赖：连续两个本地 HTTP 请求，学习 JSON 字段抽取与响应映射的最小示例，导入即可试运行。 |
| 20 | local-flag | 入参开关驱动链路：通过链路入参登记表传入开关值控制行为，适合先理解「入参登记 → 试运行预填 → 必填校验」。 |
| 30 | if-branch | 条件分支样板：按数据内容判断后走不同处理路径，IF/THEN/ELSE 基本范式。 |
| 40 | when-parallel | 并行执行样板：多路任务同时运行、结果各自写回数据空间，理解 WHEN 与并发数据隔离。 |
| 50 | for-count | 计数循环样板：固定次数重复处理，学习 FOR 配置与循环索引 $i 的用法。 |
| 60 | iterator-items | 数组迭代样板：对入参数组逐条处理，业务批处理最常用范式，含单层/嵌套扩展提示。 |
| 70 | switch-cases | 按值路由样板：根据字段值命中多个分支之一（SWITCH），未命中报错与候选清单行为可试。 |
| 80 | catch-flow | 异常捕获样板：节点失败时转入异常分支兜底，保证链路不中断的容错范式（真实触发异常路径）。 |
| 90 | script-if-booleanScript | Groovy 脚本样板：脚本节点读写数据空间、条件脚本控制 IF 分支，适合一行表达式放不下的逻辑。 |
| 100 | bpm-flow | 集成类样板（**需 BPM 环境与 bpm-default 连接器**）：会话→启流程→建 BO→附件上传/读回→完任务，BPM 全链路参考。 |

未入选但后续可由实施/运营增补：bpm-bo-update / bpm-bo-delete / bpm-rds-methods / bpm-idcard-to-userid（环境特异，作为集成类第二批）；serial-all、iterator-nested、nested-complex、nested-catch-in-then（范式与首批重复或更适合做进阶教程位）；then-empty / if-empty-false / catch-empty / and-logic / or-logic / not-logic / chain-ref（结构占位，不面向使用者）。

## 7. 实施拆步

1. **后端字段与查询**：databus_chain.sql 建表 DDL 直接加三列（不写 ALTER）；Entity/VO 三字段、BO 查询字段；queryPageList 过滤/白名单/排序分流；stats 排除模板。`mvnw compile` + IDE 0。
2. **后端模板端点与红线**：TemplateMarkBo + mark/unmark 两端点与 Service 校验（已发布拒标记、模板拒发布）；copy 返回新 id 并剥离模板字段；insert/update 通路防污染。编译 + IDE 0。
3. **SQL 收尾**：菜单权限行并入 databus_chain.sql 菜单区；databus_chain_mock_data.sql 入选 10 条 insert 直接带三列模板字段（同步更新文件头过时注释）。
4. **前端模板库**：types/API（含 copyChain 返回值）；index.vue 双 tab；ChainCard variant=template（角标/说明/拓扑/使用模板）；使用模板跳编辑器。oxlint + vue-tsc + IDE 0。
5. **前端维护入口**：TemplateMarkDialog.vue + ChainForm 模板设置区（权限点控制）。静态三绿后交用户实测。
6. **副本命名弹窗（2026-09-30 讨论追加）**：后端 ChainCopyBo + copy 端点收参 + `GET /copy-suggestion`（名称副本后缀、编码 _2/_3 递增查重）；前端 ChainCopyDialog 供普通复制/使用模板共用；ChainForm 编码框提示语换「复制-替换」话术。静态三绿后交用户实测。

## 8. 验证要点（用户亲测）

前置：**drop 重建 databus_chain 表**，依次执行 databus_chain.sql（新 DDL + 菜单）、databus_chain_mock_data.sql（含模板字段），重启后端，重新登录刷菜单。

1. mine tab 列表与计数不含任何模板；模板 tab 10 条按 sort 升序，说明文案与拓扑圆点正确，名称搜索可用、分页正常。
2. 「使用模板」：复制成功直接进入编辑器且加载的是**副本**内容；回 mine tab 可见「xxx副本」草稿；副本 is_template=0、模板说明未继承；副本可正常发布、发布后出现在手动执行下拉并可执行。
3. 模板卡片无发布/执行入口；对模板 id 直接调 publish 被拒并给出「先复制」提示；已发布链路在模板设置入口操作时被「请先下线」拦截。
4. 权限：无 `databus:editor:template` 的角色看不到任何标记入口但能浏览和使用模板；无 add 权限看不到「使用模板」。
5. 设为模板（说明必填/超长校验、排序生效）、修改设置、取消模板（字段清空、回 mine tab）全链路；模板可在编辑器内试运行验证样板可跑。
6. 删除模板后，此前复制的副本仍可正常打开发布执行（无关联影响）。
7. 回归：普通「复制」与「使用模板」均弹命名框（预填「xxx副本」与 `源编码_2` 递增建议；重复复制同一链路依次建议 `_2/_3`；编码改非法/重名被拦）；普通复制确认后留列表、使用模板确认后进编辑器；ChainForm 编码框任何状态禁用且提示「复制-替换」话术；执行记录手动执行下拉、链路卡片状态胶囊均不受影响。

## 9. 后置项（物料市场阶段再评估）

- 正式模板市场：分类（届时加 `template_category` 单列即可）、模板封面/截图、使用次数、评分、模板版本与副本升级通知、跨环境模板导入导出。
- 模板与物料市场统一 schema：模板说明/分类/适用组件元数据随组件物料一并后端化（与 roadmap 阶段 3「组件管理页后置」同一议题）。
- 进阶模板分层：当前首批是「学会一个范式」，未来可增「解决一个业务」的集成模板批（BPM BO 查改、RDS 八方法等），并配分步讲解文档链接字段。
