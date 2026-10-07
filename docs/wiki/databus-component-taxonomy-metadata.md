# 物料分类元数据后端化设计（group / domain 字典表）

> 正本：[refactor-component-workbench.md](file:///e:/01.code/databus-meta/docs/refactor-component-workbench.md) §2.2 / §4.2 / §7。
> 本文解决「怎么做」：两张字典表 DDL、两个只读端点、前端四处硬编码常量的迁移面与降级策略。
> 日期：2026-10-07。**本轮只出设计，交由另一会话实现。**

---

## 1. 背景与现状盘点

### 1.1 要解决的问题

组件台账页要改成三层树「业务组件 > 业务域名称 > 组件」。但**业务域的中文名、颜色、顺序目前全部硬编码在前端**
（[CmpPickerPanel.vue](file:///e:/01.code/plus-ui/src/views/databus/editor/components/common/CmpPickerPanel.vue) 的 `BUSINESS_SECTION_META`），
面板七组的 label/color 也硬编码在两个文件里（`PALETTE_GROUPS`、`GROUP_LABELS` / `GROUP_OPTIONS`）。
后果：新增一个业务域、改一个中文名、调一个组色，都要动前端代码并重新发版——治理属性没有落库，
与 [databus-schema-driven-form.md](databus-schema-driven-form.md) 立下的「代码/表是单一事实源」原则相悖。

需求方原话：「`BUSINESS_SECTION_META` 不应该有这种东西，而是应该来自后端。」

### 1.2 后端事实盘点：domain 的正本只能是 DB

`domain` 与 `group` 在后端的地位**完全不同**，这决定了字典表的角色差异：

| 事实 | 证据 | 影响 |
| --- | --- | --- |
| `@DatabusCmp` 注解**无 `domain()` 属性** | 属性只有 `code/name/shortName/group/icon/color/description/dataExample/nodeType/sort` | jar 内置件在编译期无处声明域 |
| `CmpSchema` record **无 `domain` 字段** | `code/name/shortName/group/icon/color/description/nodeType/editor/sort/dataExample/fields` | 注册中心不承载域 |
| `ComponentOptionVo.ofSystem()` **硬编码 `null`** 作 domain | `ComponentOptionVo.java` L65，实参位置直接写 `null` | 纯内置件（SYSTEM）恒无域 |
| `ofCustom()` 取 `row.getDomain()` | L94 | 库存件域来自 DB 列 |
| `ofOverlay()` 取 `firstNonBlank(row.getDomain(), null)` | L124，**无内置回退源** | 治理覆盖行没填域就是没有域 |
| `@DatabusCmp.group()` 有默认值 `"business"` | `DatabusCmp.java` L41 | group 有 jar 正本，DB 只是覆盖 |
| DB 列注释已写死兜底规则 | `databus_component.domain varchar(16)`：「空按 common 展示」 | **兜底规则目前在前端**，是本次要数据化的东西 |
| seed 已填 domain | `databus_component_seed.sql` 18 行：12 bpm / 6 common | 无数据迁移成本 |

结论：

- **`domain` 是纯 DB 治理列**。内置件想要域，必须在 `databus_component` 里插一行治理覆盖行（OVERLAY）。
  → `databus_component_domain` 是**唯一正本**：没有它，前端无处知道有哪些域、叫什么、什么色、谁兜底。
- **`group` 有 jar 注解正本**（`@DatabusCmp.group()`）。
  → `databus_component_group` 是**合法值白名单 + 展示元数据**，不是正本；删字典行不会让物料消失，只会让它落不进任何组。

### 1.3 前端事实盘点：四处硬编码与全部消费点

| 常量 | 定义处 | 消费点 | 迁移难度 |
| --- | --- | --- | --- |
| `PALETTE_GROUPS`（七组 key/label/color） | `editor/cmp-defs.ts` L234 | ① `palette/CmpPalette.vue` L111 import、L138 `.filter(g => g.key !== 'business')`<br>② `editor/cmp-recommend.ts` L29 import、L187-189 **同步 `findIndex`** 同分 tie-break<br>③ `common/CmpPickerPanel.vue` L131、L207 `.filter(≠business && ≠flow)`<br>④ `component/workbench/list/ComponentGrid.vue` L172、L209 `.find(...)?.label`<br>⑤ `component/workbench/tree/ComponentTree.vue` L69、L237 `for (const g of PALETTE_GROUPS)` | **高**（②是同步调用，见 §5.3） |
| `GROUP_LABELS` / `groupLabel()` | `component/model/labels.ts` L8 / L18 | labels.ts 内部 L22、L97-103 | 低 |
| `GROUP_OPTIONS`（七组 value/label/color） | `component/model/labels.ts` L96 | **仅** `component/form/tabs/AppearanceTab.vue` L10、L62 | 低 |
| `BUSINESS_SECTION_META`（三段 bpm/common/slot） | `common/CmpPickerPanel.vue` L218 | 仅该文件 L230 `businessGroups` computed | 中（`match` 是**函数字段**，不可序列化）；按 §2.3 的派生方案可**彻底删除** |

派生约束（迁移时必须一并处理，否则编译不过或静默丢件）：

- `CmpDef['group']` 是字面量联合 `'flow'|'sequence'|'branch'|'loop'|'other'|'subflow'|'business'`（cmp-defs.ts L58）；
  `bizCategory` 是 `'bpm'|'common'`（L60）→ 字典化后**必须放宽为 `string`**，否则新增域要改前端类型。
- `VALID_GROUPS`（cmp-defs.ts L272）是远程 group 白名单，非法值降级 `'business'` → 改为「字典 key 集合」，兜底组 key 从字典取。
- `STRUCTURE_DEFS` 静态件硬编码 group key：L77/87 `flow`、L100/111 `sequence`、L124/135 `branch`、
  L148/159/170 `loop`、L183/194/205/216 `other`、L229 `subflow` → **字典 key 必须与这些字面量逐字一致**（§5.3 头号风险点）。
- `api/databus/component/types.ts` L39 `ComponentDomain = 'bpm'|'common'`、L99 `domain?: ComponentDomain|null`、
  L146 `domain?: string|null` → 放宽为 `string`。
- `component/model/registry.ts` 的 `ComponentRegistryRow` **无 `domain` 字段** → 需加 `domain?: string`（§5.5）。
- 会话缓存范式：`editor/composables/useComponentOptions.ts` 用**模块级 Promise**（非组件级 ref），
  整个编辑会话只拉一次，失败不缓存结果允许重试，**失败时不做任何本地兜底**（error 态显性露出）。字典沿用同一范式。

### 1.4 命名体例的既有不一致

连接表叫 `sys_databus_connection`（带 `sys_` 前缀），组件族叫 `databus_component` / `databus_component_version`（不带）。
本次两张新表**跟随 `databus_component` 家族**（不带前缀）——它们与组件台账同族同生命周期。
既有不一致不在本轮修正范围。

---

## 2. 表设计

### 2.1 `databus_component_group`（面板分组字典）

新建 `script/sql/databus_component_dict.sql`（两表同文件，同族同批）。DDL 照 `databus_component.sql` 体例：

```sql
-- ----------------------------
-- 物料面板分组字典 databus_component_group
-- group 的正本在 jar 注解 @DatabusCmp.group()，本表只是「合法值白名单 + 展示元数据」。
-- group_key 必须与前端 STRUCTURE_DEFS 静态件的 group 字面量逐字一致，否则静态件落不进任何组。
-- ----------------------------
drop table if exists databus_component_group;
create table databus_component_group (
    id                bigint(20)      not null                  comment '主键id',
    group_key         varchar(32)     not null                  comment '分组key（flow/sequence/branch/loop/other/subflow/business，与注解 group() 及 databus_component.group_name 对齐）',
    group_name        varchar(64)     not null                  comment '分组中文标题（编辑器面板与台账树目录名）',
    color             varchar(16)     default null              comment '分组色值（面板标题圆点/树目录色点，如 #409eff）',
    sort              int(4)          default 0                 comment '显示顺序（数值越小越靠前）',
    builtin           char(1)         default 'N'               comment '是否结构组（Y=前端 STRUCTURE_DEFS 硬编码引用，不可删；N=可自由增删）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_group_key (group_key)
) engine=innodb comment='物料面板分组字典表';
```

**字段取舍说明**：

- `builtin` 保留：seed 的七组全是结构组（前端有静态件按 key 引用），标记出来后删除保护规则可以写在服务层
  （`builtin='Y'` 的行拒删），不必维护一份「哪些 key 被硬编码引用」的隐式知识。
- **不加 `visible` 列**。前端现有的 `CmpPalette.vue` L138 `.filter(g => g.key !== 'business')` 与
  `CmpPickerPanel.vue` L207 `.filter(≠business && ≠flow)` 是**视图语义**（编辑器左面板不展示业务物料，
  业务物料在另一个 tab），不是「该组被治理下线」。两件事塞进同一列会误用，且下线需求当前不存在。

### 2.2 `databus_component_domain`（业务域字典）

```sql
-- ----------------------------
-- 业务域字典 databus_component_domain
-- domain 的唯一正本：@DatabusCmp 注解与 CmpSchema 均无 domain 字段，
-- ComponentOptionVo.ofSystem() 硬编码 null。域只能来自本表 + databus_component.domain 治理列。
-- ----------------------------
drop table if exists databus_component_domain;
create table databus_component_domain (
    id                bigint(20)      not null                  comment '主键id',
    domain_key        varchar(32)     not null                  comment '业务域key（与 databus_component.domain 对齐）',
    domain_name       varchar(64)     not null                  comment '业务域中文名（台账树第二层目录标题）',
    color             varchar(16)     default null              comment '目录色值（树目录色点/选择器分段圆点）',
    sort              int(4)          default 0                 comment '显示顺序（数值越小越靠前）',
    is_default        char(1)         default 'N'               comment '是否兜底域（Y=databus_component.domain 为空的件归入此域；全表至多一行 Y）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_domain_key (domain_key)
) engine=innodb comment='物料业务域字典表';
```

**`is_default` 是本设计的关键增益点**：`databus_component.domain` 列注释里那句「空按 common 展示」
目前是**前端硬编码规则**（`BUSINESS_SECTION_META` 里 common 段的 `match: (d) => d.bizCategory !== 'bpm'` 就是它的实现）。
把它数据化成一个列，前端就只需读「哪一行是兜底域」，将来把兜底域从 common 换成别的、或加第三个域，都不用改代码。

不加 `applicable_group` 列：「域只对 `group=business` 生效」是**树的形状规则**（三层只在 business 组出现），
属前端布局逻辑；字典表本身不带组约束。将来若别的组也要分域，字典表不用改，只改树的分组逻辑。

### 2.3 「条件组件」入域表：派生而非存储（2026-10-07 定案）

前端 `BUSINESS_SECTION_META` 现有三段：`bpm` / `common` / `slot`（条件组件）。
`slot` 段的判定是 `!!d.lfNodeType && d.lfNodeType !== 'NodeComponent'`——它筛的是 **LiteFlow 节点类型属性**
（只能放进 IF/WHILE 条件槽的算子配件），而不是人工指派的治理属性。

**决定：域表增第三行 `slot`**，让「业务组件下的分段全部来自后端字典」这句话没有例外，
前端 `BUSINESS_SECTION_META` 可以彻底删干净。

但 `slot` 行的**成员判定不能靠人工填 `databus_component.domain`**。查证事实：

| 事实 | 证据 |
| --- | --- |
| 条件槽件共 4 个：`forLoop` / `iteratorLoop` / `switchRoute` / `booleanScript` | 前三者 `@DatabusCmp(nodeType = FOR/ITERATOR/SWITCH)`；`booleanScript` 由 `ComponentSchemaScanner.registerSupplement()` L100-102 补充注册（非 Spring bean，扫不到注解），`NodeTypeKind.BOOLEAN` |
| **这 4 件在 seed 里没有 DB 行** | `databus_component_seed.sql` 全文搜 `forLoop\|iteratorLoop\|switchRoute\|booleanScript` **零命中**；18 行 seed 的 domain 全填了（12 bpm / 6 common） |
| 故它们在 `/options` 里是 SYSTEM 源，`domain` 恒为 `null` | `ComponentOptionVo.ofSystem()` L65 硬编码 `null` |

若走「补治理行」路线，要给这 4 件各插一行 OVERLAY 只为填 `domain='slot'`，
且**以后每加一个条件槽件都得记得补行**——这是一条隐式知识，漏了就把算子件混进「通用组件」段，
正是 `BUSINESS_SECTION_META` 注释里写的「与普通业务叶子混排会误导」。

**故采用派生**：字典表里 `slot` 行只提供展示元数据（label/color/sort），
成员判定由后端在 `/options` 合流时按 `node_type` 派生，规则集中一处，新增槽件自动归位、零补行。

```java
/** 条件槽件域名（与 databus_component_domain 的 slot 行 key 一致） */
private static final String DOMAIN_SLOT = "slot";

/** node_type 是槽件的正本：非 NODE 即算子条件位，恒归 slot 域 */
private static String deriveSlotDomain(NodeTypeKind nodeType) {
    return (nodeType == null || nodeType == NodeTypeKind.NODE) ? null : DOMAIN_SLOT;
}
```

`ComponentOptionVo` 三个工厂的改动：

| 工厂 | 现状 | 改为 | 理由 |
| --- | --- | --- | --- |
| `ofSystem()` | L65 硬编码 `null` | `deriveSlotDomain(cmp.nodeType())` | 4 个槽件走这支，是派生的主要落点 |
| `ofOverlay()` | L124 `firstNonBlank(row.getDomain(), null)` | `firstNonBlank(row.getDomain(), deriveSlotDomain(sys.nodeType()))` | 显式填值优先（DB 是治理正本），未填则按内置 node_type 派生 |
| `ofCustom()` | L94 `row.getDomain()` | **不变** | 库存件契约/治理全量取 DB，无内置 node_type 可派生 |

派生后 `/options` 的 `domain` 字段语义变为「bpm / common / slot / null（未指派的普通叶子）」，
前端 `cmp-defs.ts` L309 的白名单 `(opt.domain === 'bpm' || opt.domain === 'common')` 必须放宽（§5.6）。

**保留在前端的部分**：推荐引擎 `cmp-recommend.ts` 对槽件**降权**仍按 `lfNodeType` 判定。
那是行为语义（能不能拖进条件槽），不是分类语义，不该也不需要由域字典承载。

**新增的一致性风险**：`domain='slot'` 与 `node_type≠NODE` 从此是两处真相。
若有人在 `databus_component` 里给一个 `node_type='NODE'` 的件手填 `domain='slot'`，
`ofOverlay` 的「显式优先」会让它进条件组件段，但它拖不进条件槽——前端表现为可分组不可用。
缓解：`AppearanceTab` 的「业务域」下拉里，**当 `node_type ≠ NODE` 时锁定为 `slot` 不可改，
当 `node_type = NODE` 时不显示 `slot` 选项**（§5.4）。

### 2.4 seed 数据与 ID 段

`insert ignore` 幂等（照 `databus_component_seed.sql` 体例）。

**ID 段**：现有占用为 connection 菜单 `1762000000000000010~015`、execution 菜单 `1762000000000000040~043`、
component 菜单 `1761400000000020009~014`（父 `1761400000000020000`）、
seed 组件数据 `1762000000001000001` 起、seed 版本数据 `1762000000001100001` 起。
→ **新字典数据用 `1762000000001200001` 起**（group 七行 `...200001~200007`，domain 三行 `...200101~200103`）。

```sql
-- 七组 key 必须逐字抄前端 STRUCTURE_DEFS 的 group 字面量
insert ignore into databus_component_group
  (id, group_key, group_name, color, sort, builtin, create_time, remark) values
  (1762000000001200001, 'flow',     '流程节点',   '#909399', 10, 'Y', sysdate(), '开始/结束等流程锚点'),
  (1762000000001200002, 'sequence', '顺序编排',   '#409eff', 20, 'Y', sysdate(), 'THEN 串行'),
  (1762000000001200003, 'branch',   '条件分支',   '#e6a23c', 30, 'Y', sysdate(), 'IF/SWITCH'),
  (1762000000001200004, 'loop',     '循环迭代',   '#67c23a', 40, 'Y', sysdate(), 'FOR/WHILE/ITERATOR'),
  (1762000000001200005, 'other',    '异常与逻辑', '#f56c6c', 50, 'Y', sysdate(), 'CATCH/AND/OR/NOT'),
  (1762000000001200006, 'subflow',  '子流程',     '#909399', 60, 'Y', sysdate(), '链路嵌套'),
  (1762000000001200007, 'business', '业务组件',   '#409eff', 70, 'Y', sysdate(), '业务物料，按 domain 二次分组');

insert ignore into databus_component_domain
  (id, domain_key, domain_name, color, sort, is_default, create_time, remark) values
  (1762000000001200101, 'bpm',    'BPM 平台', '#7c3aed', 10, 'N', sysdate(), 'BPM 平台集成件'),
  (1762000000001200102, 'common', '通用组件', '#67c23a', 20, 'Y', sysdate(), '通用数据加工件；domain 为空的件兜底归此域'),
  (1762000000001200103, 'slot',   '条件组件', '#e6a23c', 30, 'N', sysdate(), '算子条件位件（forLoop/iteratorLoop/switchRoute/booleanScript）；成员由后端按 node_type≠NODE 派生，不需人工指派');
```

（label/color/sort 逐字抄自现有 `PALETTE_GROUPS` 与 `BUSINESS_SECTION_META`，保证迁移后视觉零变化。）

**零新菜单**：两个端点沿用 `databus:editor:list` 权限，**不新增任何 `sys_menu` 行**，非 admin 账号无需重新授权。

---

## 3. 端点设计

### 3.1 `GET /databus/component/groups`

```java
/**
 * 查询物料面板分组字典（编辑器面板与台账树共用，不分页）
 */
@SaCheckPermission("databus:editor:list")
@GetMapping("/groups")
public R<List<ComponentGroupVo>> groups() {
    return R.ok(componentService.queryGroups());
}
```

VO 照 `ComponentOptionVo` 体例（record + `@JsonInclude(NON_NULL)`）：

```java
/**
 * 物料面板分组字典项。
 *
 * @param key   分组key（与 @DatabusCmp.group() 及 databus_component.group_name 对齐）
 * @param label 分组中文标题
 * @param color 分组色值（面板圆点/树目录色点）
 * @param sort  显示顺序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentGroupVo(String key, String label, String color, Integer sort) {
}
```

排序 `sort asc, id asc`。不包络（`/options` 用 `ComponentOptionsVo(schemaVersion, components)`
是为承载 schema JSON 契约演进；分组字典是扁平字符串，无演进压力）。

### 3.2 `GET /databus/component/domains`

同权限、同体例。VO 多一个兜底标记：

```java
/**
 * 物料业务域字典项。
 *
 * @param key       业务域key（与 databus_component.domain 对齐）
 * @param label     业务域中文名（台账树第二层目录标题）
 * @param color     目录色值
 * @param sort      显示顺序
 * @param isDefault 是否兜底域（domain 为空的件归入此域；全表至多一行为 true）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ComponentDomainVo(String key, String label, String color, Integer sort, Boolean isDefault) {
}
```

⚠️ record component 名用 `isDefault`（不是 `default`，Java 关键字）。Jackson 对 record 按 component name 序列化，
JSON key 即 `isDefault`，前端接口名对齐。

### 3.3 服务层与 Mapper

`IDatabusComponentService` 加两方法 `queryGroups()` / `queryDomains()`。
Mapper 走 `BaseMapperPlus`，**无需 XML**（照 `SysDatabusConnectionMapper` 的注释：
`BaseMapperPlus` 已提供 `selectVoList` / `selectVoById` / `insert` / `updateById` / `deleteByIds` 全部能力）：

```java
public interface DatabusComponentGroupMapper extends BaseMapperPlus<DatabusComponentGroup, ComponentGroupVo> {
}
```

实体照 `DatabusComponent` 体例（`@TableName` + `@TableId` + `@TableLogic` + 继承 `BaseEntity`）。

**本轮不做增删改端点、不做 BO、不做字典管理页**。理由：字典是低频治理数据，
当前唯一需求方是前端读取；补写端点要连带 BO 校验、菜单权限、`is_default` 唯一性校验、管理页 UI，
超出「元数据后端化」的必要范围。后续若要做字典管理页，再补 BO + 写端点 + 菜单权限，
**届时必须在服务层校验 `is_default='Y'` 全表至多一行**（本轮靠 seed SQL 保证）。

### 3.4 缓存

不加后端缓存。两表合计 <20 行、走唯一索引全表扫，单次查询成本可忽略；
`@Cacheable` 反而引入「改了字典但页面不变」的排障成本。
前端做会话级缓存即可（§5.2）。

---

## 4. `/options` 的 domain 补全：两件事分开处理

`ofSystem()` 硬编码 `null`、`ofOverlay()` 无回退源 → jar 内置件即使 DB 没治理行也拿不到域。
「补全」其实是两件性质不同的事，结论也不同：

| | 事 | 谁做 | 理由 |
| --- | --- | --- | --- |
| ① | **`slot` 域指派** | **后端派生**（§2.3，`deriveSlotDomain(nodeType)`） | `node_type` 是 jar 编译期正本，规则确定、无治理裁量空间；放前端就要复制一份 `lfNodeType` 判定，正是 `BUSINESS_SECTION_META` 现在的样子 |
| ② | **空 domain 兜底进哪个域** | **前端读字典 `isDefault`** | 「谁是兜底域」是治理决定，数据化在 `is_default` 列。`@JsonInclude(NON_NULL)` 契约里 `null` =「未指派」**是有信息量的**——前端能区分「显式归入通用域」和「压根没指派」，台账树可据此给未指派件加提示。后端补全会永久丢掉这个区分 |

故 `databus_component.domain` 的 DDL 注释「空按 common 展示」应改写为
「空＝未指派：普通叶子由前端按 `databus_component_domain.is_default` 行归入兜底域；
`node_type ≠ NODE` 的件由后端派生为 `slot`，不走兜底」。

派生后 `/options` 下发的 `domain` 有四种取值：`bpm` / `common` / `slot` / `null`。

---

## 5. 前端迁移面

### 5.1 API 层

`src/api/databus/component/index.ts` 加两函数（照 `listComponentOptions` 体例）：

```ts
/** 面板分组字典 GET /databus/component/groups（权限 databus:editor:list） */
export function listComponentGroups(): AxiosPromise<ComponentGroupOption[]> { … }
/** 业务域字典 GET /databus/component/domains（权限 databus:editor:list） */
export function listComponentDomains(): AxiosPromise<ComponentDomainOption[]> { … }
```

`types.ts`：加 `ComponentGroupOption` / `ComponentDomainOption` 接口；
**`ComponentDomain` 联合类型（L39）放宽为 `string`**，`ComponentOption.domain`（L99）随之改 `string | null`。

### 5.2 新增字典 composable（域内共享）

新建 `src/views/databus/editor/composables/useComponentTaxonomy.ts`，编辑器与台账共用。
沿用 `useComponentOptions` 的**模块级 Promise** 范式（多组件同时挂载只发一个请求，失败不缓存可重试）：

```ts
let pending: Promise<void> | null = null;
const groups = shallowRef<ComponentGroupOption[]>([...FALLBACK_GROUPS]);   // 编译期兜底
const domains = shallowRef<ComponentDomainOption[]>([]);
const loaded = ref(false);
const error = ref(false);

/** 兜底域 key（domain 为空的件归此域）；字典未加载时回退 'common' */
const defaultDomainKey = computed(() => domains.value.find(d => d.isDefault)?.key ?? 'common');
```

与 `useComponentOptions` **无依赖关系**，调用侧可 `Promise.all` 并行拉取。

### 5.3 四处常量的替换点

| 常量 | 处置 | 说明 |
| --- | --- | --- |
| `PALETTE_GROUPS` | **保留为编译期兜底，重命名 `FALLBACK_GROUPS`**；改用 `shallowRef` 承载运行时值 | 见下方「同步读点」说明 |
| `GROUP_LABELS` / `groupLabel()` | `groupLabel(key)` 改读字典 Map | 三级回退：字典 → `FALLBACK_GROUPS` → 原 key |
| `GROUP_OPTIONS`（labels.ts L96） | **删除** | 唯一消费方 `AppearanceTab.vue` L10/L62 改读字典 |
| `BUSINESS_SECTION_META`（CmpPickerPanel.vue L218） | **彻底删除**（三段全来自字典，零残留派生规则） | `businessGroups` computed 改为按字典行分桶：`domains.value.map(d => ({ ...d, defs: allDefs.value.filter(x => x.group === 'business' && (x.domain ?? defaultDomainKey.value) === d.key) })).filter(s => s.defs.length > 0)`。函数字段 `match` 消失，改纯字符串比对——后端派生 `slot` 后（§2.3）前端不再需要 `lfNodeType` 特判 |

**同步读点（迁移的最大技术障碍）**：`cmp-recommend.ts` L187-189 在同分 tie-break 里做
`PALETTE_GROUPS.findIndex((g) => g.key === a.def.group)`——这是**同步调用**，处在推荐引擎打分链路里。
若把分组改成异步加载后再渲染，会牵动整条推荐调用链。

解法：不要把它改成异步，而是**让它读的容器变成响应式**。
`PALETTE_GROUPS` 从 `export const 数组字面量` 改为 `export const paletteGroups = shallowRef([...FALLBACK_GROUPS])`，
字典到位后 `paletteGroups.value = 字典映射结果`。所有消费点从 `PALETTE_GROUPS` 改成 `paletteGroups.value`
（模板里自动解包）。同步读点读到的永远是「当前值」——字典没到就用兜底，到了就自动切，无需 await。

**头号风险点**：`STRUCTURE_DEFS` 静态件的 group key 是**硬编码字面量**（cmp-defs.ts L77~L229 共 12 处）。
字典 key 若与之不逐字一致，`CmpPalette` / `ComponentTree` 的 `filter`/`map` 会**静默丢件**（不报错，面板就是少了节点）。
故 seed SQL 的七个 key 必须抄 `STRUCTURE_DEFS`，且实施后要逐组核对面板件数与迁移前一致（§6 验收）。

其余类型放宽：`CmpDef['group']`（cmp-defs.ts L58）与 `bizCategory`（L60）两个联合类型改 `string`；
`VALID_GROUPS`（L272）改为「字典 key 集合」，非法值降级到字典里的兜底组（`business`）。

### 5.4 台账树三层结构

`ComponentTree.vue` L237 `for (const g of PALETTE_GROUPS)` → 改字典；
business 组下**只按 `row.domain` 字符串分桶**，空值归 `defaultDomainKey`：

```ts
const sections = domains.value.map((d) => ({
  ...d,
  defs: rows.value.filter((r) => r.group === 'business'
    && (r.domain || defaultDomainKey.value) === d.key)
})).filter((s) => s.defs.length > 0);
```

**没有 `slot` 特判分支**。后端派生（§2.3）已让 4 个槽件带着 `domain='slot'` 下发，
前端分桶逻辑对三段一视同仁，`lfNodeType` 判定从树里彻底消失（编辑器选择器同理，§5.3）。
槽件在树里落到哪一段，完全由字典行的 `sort` 决定，不再是前端硬编码的「末段」。

树的层级竖线、右键菜单、「新建组件」入口迁移等前端细节见正本 §2.2 / §2.3，不在本文范围。

`AppearanceTab.vue` 的「业务域」下拉：

- 选项来自 `domains` 字典，**仅 `form.groupName === 'business'` 时显示**（其余六组无域概念）。
- **`slot` 行的可选性由 `node_type` 决定**（§2.3 的一致性缓解措施，必须实现）：

| `node_type` | 下拉行为 | 理由 |
| --- | --- | --- |
| ≠ `NODE`（算子条件位） | 锁定为 `slot`，`disabled` 不可改 | 它必然进条件组件段；放开就会造出「在条件段但拖不进条件槽」的矛盾件 |
| = `NODE`（普通叶子） | 选项列表里**剔除 `slot`** | 普通叶子进条件段等于承诺了一个它兑现不了的行为 |
| 库存脚本件（无内置 `node_type`） | 全部三行可选 | 契约全量取 DB，`node_type` 由 `param_schema` 自定义，无法静态判定 |

### 5.5 `registry.ts` 加 domain

`ComponentRegistryRow` 加 `domain?: string`，`buildRegistry` 三个分支各自填：

| 分支 | 取值 | 说明 |
| --- | --- | --- |
| SYSTEM（L72） | `opt.domain ?? undefined` | 派生后 4 个槽件在此支带 `'slot'`，不再是恒 undefined |
| OVERLAY / CUSTOM（L91） | `db?.domain \|\| opt.domain \|\| undefined` | `opt.domain` 已含后端派生结果，DB 空值时自然回退 |
| 停用件（L116） | `db.domain ?? undefined` | 无 `/options` 对应项，只能取 DB |

兜底域归并**不在 `buildRegistry` 里做**（保持它是纯合流函数），由树/网格的分组 computed 消费 `defaultDomainKey` 时归并。

### 5.6 `cmp-defs.ts` 的域白名单放宽

`toCmpDef()` L309 现在写死了两个域，且用 `lfNodeType` 排除槽件：

```ts
// 现状：业务域只落普通叶子；槽件（BOOLEAN/FOR/ITERATOR/SWITCH）归「条件组件」段，不带域
if (!lfNodeType && (opt.domain === 'bpm' || opt.domain === 'common')) {
  def.bizCategory = opt.domain;
}
```

两处都与 §2.3 冲突：后端派生后槽件**恰恰带 `domain='slot'`**，`!lfNodeType` 守卫会把它丢掉；
两值白名单会让将来新增的域（如 `ai`）静默失效——正是本次要根治的「新增域必须改前端」问题。

改为无条件透传：

```ts
// 域由后端下发（含 slot 派生结果），前端不再做值白名单与 nodeType 守卫
if (opt.domain) def.bizCategory = opt.domain;
```

配套类型放宽：`CmpDef['bizCategory']`（cmp-defs.ts L60）由 `'bpm'|'common'` 改为 `string`；
`ComponentDomain`（types.ts L39）同步放宽（§5.1）。

**注意 `bizCategory` 与 `lfNodeType` 从此正交**：槽件同时带 `lfNodeType`（拖拽行为）与 `bizCategory='slot'`（分组归属）。
`cmp-recommend.ts` 的槽件降权仍只读 `lfNodeType`（§2.3），不受影响；
编辑器选择器 `businessGroups` 只读 `bizCategory`（§5.3），`match` 函数字段随 `BUSINESS_SECTION_META` 一起删除。

---

## 6. 实施顺序与验收

**批次**（每步单独可验证，可分批提交）：

1. SQL：新建 `script/sql/databus_component_dict.sql`（两表 DDL + seed，§2.1/§2.2/§2.4）；
   顺手改 `databus_component.sql` 的 `domain` 列注释（§4 结论）
2. 后端：两实体 + 两 Mapper + 两 VO record + 服务两方法 + 控制器两端点
3. 后端：`ComponentOptionVo` 的 `deriveSlotDomain()` 派生 + 三工厂改动（§2.3）。
   **此步可独立验证**：只改派生不改字典，`/options` 里 4 个槽件就该带上 `domain='slot'`；
   此时前端 L309 白名单会把它丢掉（视觉无变化），故对现网零风险，适合先落
4. 前端 API 层 + `types.ts` 类型放宽（§5.1）
5. `useComponentTaxonomy.ts` composable（§5.2）
6. 四处常量替换，按 §5.3 表格顺序：`GROUP_OPTIONS`（最简单，单消费点）→ `GROUP_LABELS` →
   `BUSINESS_SECTION_META` → `PALETTE_GROUPS`（最难，含同步读点改造）；
   同期做 §5.6 的 `cmp-defs.ts` L309 放宽（它是 `BUSINESS_SECTION_META` 删除的前置条件）
7. 台账树三层分组 + `registry.ts` 加 domain + `AppearanceTab` 业务域下拉（§5.4/§5.5）

**验收要点**：

- `/groups` 返回七组，key 与 `STRUCTURE_DEFS` 字面量逐字一致，顺序 = seed 的 sort
- `/domains` 返回 **bpm / common / slot 三行**，`common.isDefault === true`，且全表只有一行 true
- `/options` 里 `forLoop` / `iteratorLoop` / `switchRoute` / `booleanScript` **四件均 `domain === 'slot'`**，
  且它们的 `source` 仍是 `SYSTEM`（未给它们插任何 DB 行）
- **视觉零变化**：迁移完成后编辑器面板七组标题/圆点色、选择器业务三段标题/色，与迁移前逐像素一致
  （label/color/sort 都抄的现有常量，任何差异都是迁移错误）
- **件数零丢失**：逐组核对面板件数与迁移前一致（防 §5.3 头号风险点）；
  特别核对「条件组件」段仍是 4 件（防 §5.6 的 `!lfNodeType` 守卫漏删）
- 直接改 `databus_component_group.group_name` 后刷新页面，编辑器面板标题与台账树目录名同步变化，**无需前端发版**
- 新增一行 domain（如 `ai`）后刷新，选择器业务段与台账树第二层各多一个目录，**无需前端发版**
- `databus_component.domain` 置空的件落到兜底域目录
- 给一个 `node_type='NODE'` 的件手填 `domain='slot'`，`AppearanceTab` 下拉按 §5.4 规则剔除/锁定该选项
- 字典端点失败时：编辑器面板仍能用 `FALLBACK_GROUPS` 渲染七组（不白屏），台账树退化为两层，error 态显性露出可重试
- `cmp-recommend.ts` 同分 tie-break 行为不变（推荐顺序与迁移前一致），槽件降权行为不变
- 零新菜单：非 admin 账号有 `databus:editor:list` 即可访问两端点

---

## 7. 未决

- ~~**`slot`（条件组件）是否入域表**~~ **已决（2026-10-07）**：入表作第三行，成员由后端按 `node_type ≠ NODE` 派生（§2.3）。
- **`domain='slot'` 与 `node_type≠NODE` 双真相的冲突优先级**：本轮靠 §5.4 的下拉锁定规则在**入口**堵住不一致，
  但直连 DB 改数据仍可造出矛盾件（如给 `node_type='NODE'` 的件填 `domain='slot'`）。
  当前行为是「显式填值优先」（`ofOverlay` 的 `firstNonBlank`），即矛盾件会进条件段但拖不进条件槽。
  若将来要求后端强一致，需定义「派生覆盖显式值」还是「拒绝保存」——本轮不做，靠入口约束。
- **`category` 五分类本轮不迁**：`CATEGORY_OPTIONS`（labels.ts L87）是台账表列 `databus_component.category`
  的正本（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM），与 `group` 是**两套并行口径**（一个是台账分类，
  一个是面板分组）。迁它收益低、且会引出「两套口径是否该合一」这个更大的问题，本轮不碰。
- **字典写端点 / 管理页**：本轮不做（§3.3）。做的时候必须补 `is_default` 唯一性校验与 `builtin='Y'` 删除保护。
- **`is_default` 出现多行 `Y` 时的行为**：本轮无写端点，靠 seed 保证单行。
  前端 `defaultDomainKey` 的实现是 `.find(d => d.isDefault)`，多行时静默取第一个——
  补写端点时应在服务层拦住，而不是让前端容错。
