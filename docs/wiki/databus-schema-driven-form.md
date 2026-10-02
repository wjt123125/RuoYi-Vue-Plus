# schema-driven 配置表单设计（AI 原子物料生产线 · 第一步）

> 正本：[databus-ai-atom-material.md](databus-ai-atom-material.md) §4 第一步。
> 本文解决「怎么做」：注解模型、Schema 契约、反射扫描、合流接口、前端递归表单、setValue 试点与推广批次。
> 日期：2026-10-02。

---

## 1. 背景与现状盘点

### 1.1 要解决的问题

业务组件的节点配置（`cmpData`，EL 节点 `.data("...")` 里的 JSON）目前主要靠用户在
[CmpProps.vue](file:///e:/01.code/plus-ui/src/views/databus/editor/components/panels/CmpProps.vue)
的 JsonCodeEditor 里**手写 JSON**。只有 forLoop / iteratorLoop / switchRoute 三个件有手写结构化表单，
script / booleanScript 有专用脚本编辑器。物料增加后，手写 JSON 的配置成本不可接受，
也无法支撑第二步脚本原子「注册即可配置」与第三步 AI 自动生成。

目标：**代码（Cfg 类 + 注解）是配置 schema 的单一事实源**，前端按 schema 递归渲染表单，
JSON 编辑器降级为逃生舱。业界（n8n INodeType、Activepieces Property、NiFi PropertyDescriptor、
Windmill 参数反推）均为代码即 schema，无主流产品给内置件手抄第二份 JSON，取证见正本 §3.1。

### 1.2 后端事实盘点（22 个 Cfg 类 / 23 个业务物料）

所有业务组件：

- 类上标 `@LiteflowComponent("注册名")`，继承 [DatabusNodeComponent](../ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/component/DatabusNodeComponent.java)；
- 配置类为独立 `XxxCfg`（Lombok `@Data`），`process()` 第一行 `this.getCmpData(XxxCfg.class)` 由 LiteFlow 按 JSON 反序列化；
- 物料清单（注册名 / Cfg / 字段形态）：

| 物料 code | Cfg 类 | 字段形态分类 |
| --- | --- | --- |
| setValue | SetValueCfg | 扁平：String path(TARGET) + Object value(表达式/字面量) |
| condition | ConditionCfg | 扁平：String path(DATA) + String op(枚举) + Object value |
| response | ResponseCfg | 扁平：Object result + Object msg + String dataPath(DATA) |
| forLoop | ForLoopCfg | 扁平：Object count(DATA/数字) + String indexVar |
| iteratorLoop | IteratorLoopCfg | 扁平：String source(DATA) + String indexVar |
| switchRoute | SwitchRouteCfg | 行编辑 cases[{Object value, String target}]，**target 候选来自画布 case 名** |
| fieldMap | FieldMapCfg | 行编辑 mappings[{String from(DATA), String to(TARGET), String type(枚举)}] |
| dataPatch | DataPatchCfg | String target(TARGET) + Map<String,Object> patch（KV，值可表达式） |
| httpRequest | HttpRequestCfg | 最复杂：Map headers/query + Object body + 嵌套 auth + 行编辑 mappings + List<String> responseHeaders + 枚举 method/bodyType |
| sessionCreate | SessionCreateCfg | 扁平 6 String（connectionId + userName/password 等） |
| processStart | ProcessStartCfg | 扁平 4 String（title 可嵌表达式） |
| processTerminate | ProcessTerminateCfg | 扁平 3 String（instanceId DATA） |
| taskComplete | TaskCompleteCfg | 扁平 3 String + Boolean failOnError |
| boCreate | BoCreateCfg | 行编辑 boList[{boName, sourcePath(DATA), 嵌套 rewrite{strategy 枚举, path(TARGET), 3×List<String>}}] |
| boQuery | BoQueryCfg | 嵌套 main（6 字段）+ 行编辑 relate[{3 String}] + List<String> sub |
| boUpdate | BoUpdateCfg | 行编辑 boList[{boName, sourcePath(DATA)}] |
| boDelete | BoDeleteCfg | 扁平 method + 行编辑 boList[{boName, sourcePath}] |
| rdsExecute | RdsExecuteCfg | 扁平 String 枚举 + **Object sql + Object args** + 2 Integer |
| idCardToUserId | IdCardToUserIdCfg | 行编辑 fields[{path(DATA), separator}] |
| fileUpload | FileUploadCfg | 扁平 8 String + Boolean validateChecksum（多 DATA 路径） |
| fileDownload | FileDownloadCfg | 扁平 3 String（boId DATA） |
| script / booleanScript | ScriptCfg | **非 Spring bean、无 @LiteflowComponent**，共用 Cfg（language + script） |

形态归并为六类：**扁平标量 / 嵌套对象 / 对象数组行编辑 / 标量数组 / KV Map / Object 任意值**。
另有两个横切特征：12 个 BPM 件重复出现 `connectionId`（领域控件 connectionSelect）；
字段分「要数据 DATA」「起名字 TARGET」两种表达式角色（约定正本：project_memory 字段角色铁律）。

### 1.3 前端事实盘点

- [cmp-defs.ts](file:///e:/01.code/plus-ui/src/views/databus/editor/cmp-defs.ts)：硬编码 23 个业务物料 + 14 个虚拟/算子节点的
  label/icon/color/group/lfNodeType/desc，是面板拖拽、节点渲染、EL 生成的物料源。
- CmpProps.vue 按节点类型分五支：虚拟/槽位不可编辑、算子网关（tag + SWITCH case 名）、
  forLoop/iteratorLoop/switchRoute 手写表单、**其余业务件＝数据空间名 + JsonCodeEditor**、脚本件专用编辑器。
  （2026-10-02 推广批后的现状：五支中的手写表单仅留 switchRoute；forLoop/iteratorLoop 已并入
  schema 表单分支，白名单拆除，见 §6.6/§8）
- 节点配置在 ElNode 模型上就是 `data: string`（JSON 字符串），保存时进 canvas_data，发布时进 EL `.data()`，
  后端运行时 `getCmpData` 反序列化——**本设计不改这个存储格式**。
- [JsonCodeEditor.vue](file:///e:/01.code/plus-ui/src/views/databus/editor/components/common/JsonCodeEditor.vue)：
  CodeMirror 6 + JSON 实时语法诊断 + 格式化/压缩，成熟可留用。
- `/databus/component/options` 后端已存在但只查 `databus_component` 空表（返回空数组），前端从未调用；
  [DatabusComponent](file:///e:/01.code/RuoYi-Vue-Plus/ruoyi-modules/ruoyi-databus/src/main/java/org/dromara/databus/domain/DatabusComponent.java)
  已预留 param_schema / input_schema / output_schema 列。
- 连接选择无现成数据源：编辑器侧没有「全部连接」轻量接口，`/databus/connection/list` 是分页接口且要 `databus:connection:list` 权限。
- 技术栈：Vue 3.5 + Element Plus 2.14（el-input-tag、el-collapse 可用）。

### 1.4 目标与非目标

**目标**

1. 后端：两个注解 + 启动反射，把 Cfg 类变成结构化组件 schema；`/options` 一个接口合流内置件与 DB 扩展件。
2. 前端：一个自研递归 SchemaForm 覆盖六类形态；setValue 试点跑通后推广（原计划分批，
   2026-10-02 拍板 19 件一锅烩，见 §8）。
3. cmpData 存储格式不变、零 DDL、零迁移；表单与 JSON 双模式可互切（坏态各自保留）。
4. 组件管理页解锁为只读台账（内置/自定义合流展示）。

**非目标（v1 明确不做）**

- 不做 input/output schema 与连线校验（表列预留，继续空着）。
- 不做自定义校验规则 DSL、不做服务端 schema 校验层（内置件运行时已有各自必填抛错；服务端校验是第二步脚本宿主的事）。
- 不做 schema 可视化编辑器；不做启动同步器，Java 件不物化入库。
- 不退役 cmp-defs.ts（面板/拖拽/EL 仍消费它；待合流接口稳定后单独切换，见 §9）。
- 不迁移 script / booleanScript（专用编辑器保持）、不迁移 switchRoute（cases.target 候选来自画布结构，schema 表达不了）。
- 不做表达式路径自动联想（v1 只做插入按钮 + 文案提示）。

---

## 2. 总体方案与数据流

```
Cfg 类字节码 + @DatabusCmp/@DatabusProp 注解
        │  启动反射一次（ComponentSchemaScanner）
        ▼
ComponentSchemaRegistry（内存 Map<code, CmpSchema>，不可变缓存）
        │  GET /databus/component/options
        ├── 内置反射结果（source=SYSTEM）
        └── databus_component 启用行（source=CUSTOM，param_schema 同构 JSON）  ← 第二步脚本原子才有写入方
        ▼
前端 useComponentOptions()（编辑器加载一次，模块级缓存）
        ▼
CmpProps 属性面板：SchemaForm（表单模式）⇄ JsonCodeEditor（JSON 高级模式 tab）
        ▼
ElNode.data（JSON 字符串，格式不变）→ canvas_data / EL .data() → getCmpData(XxxCfg.class)（后端零改动）
```

关键取舍：**schema 用自定义轻量描述符，不用标准 JSON Schema**。
JSON Schema 的 oneOf / $ref / if-then-else 对我们六个 widget 是过度表达，自研表单还得写一堆适配；
n8n（INodeType params 数组）、Activepieces（Property 定义）、NiFi（PropertyDescriptor）全是自定义描述符。
DB 表 `param_schema` 列存的就是这套描述符的 JSON 序列化，文字上仍是 JSON，但不追求 JSON Schema 标准合规。

---

## 3. 后端：注解模型

新包 `org.dromara.databus.component.schema`（annotation / model / scanner / registry 四组类）。

### 3.1 @DatabusCmp（类级，打在 Component 上）

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface DatabusCmp {

    /** 注册名；缺省取同类型 @LiteflowComponent 的 value */
    String code() default "";

    /** 物料名（面板/台账展示）。实施时与 cmp-defs.ts label 逐项对齐 */
    String name();

    /** 物料网格短名，可选 */
    String shortName() default "";

    /** 物料分组：flow/sequence/branch/loop/other/subflow/business，业务件默认 business */
    String group() default "business";

    /** Iconify 图标名，如 ph:pencil-simple */
    String icon();

    /** 面板色值，如 #67c23a */
    String color() default "#409eff";

    /** 一句话描述（cmp-defs.desc 同源） */
    String description();

    /** LiteFlow 节点类型，默认普通件；布尔/循环/选择件显式声明 */
    NodeTypeKind nodeType() default NodeTypeKind.NODE;

    /** 面板/台账排序，缺省 100 */
    int sort() default 100;

    /** 配置类（反射字段的根），显式绑定，不做字节码猜测 getCmpData 泛型 */
    Class<?> cfg() default void.class;
}
```

> 实施补全（2026-10-02，第一步落地）：`cfg()` 设缺省哨兵 `void.class`。形态①（Java 件）
> 仍必填——扫描器对 `void.class` fail-fast；形态②（javax-pro 脚本件）固定寻找脚本类内嵌
> Cfg（§5.5.5），允许缺省，第二步零改注解。

`NodeTypeKind` 枚举：`NODE / BOOLEAN / FOR / ITERATOR / SWITCH`，对齐 cmp-defs.lfNodeType。

为什么 `cfg()` 显式声明：Cfg 类是在 `process()` 方法体里以 `getCmpData(XxxCfg.class)` 引用的，
自动发现需解析方法体字节码，黑魔法且 IDE 重构易断；注解与 `@LiteflowComponent` 同处一类，
注册名、中文名、配置类一眼看全。

示例（试点件全貌）：

```java
@Slf4j
@LiteflowComponent("setValue")
@DatabusCmp(
    code = "setValue", name = "赋值", icon = "ph:pencil-simple", color = "#67c23a",
    description = "把值（常量或 {{ $.路径 }} 表达式取值）写入上下文 $.数据空间.path",
    cfg = SetValueCfg.class, sort = 10
)
public class SetValueComponent extends DatabusNodeComponent { ... }
```

### 3.2 @DatabusProp（字段级，打在 Cfg 类的字段上）

```java
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DatabusProp {

    /** 展示名；缺省用字段名 */
    String label() default "";

    /** 说明文案（表单项下方小灰字） */
    String description() default "";

    /** 输入控件；缺省按 Java 类型推断（§3.4） */
    WidgetKind widget() default WidgetKind.AUTO;

    /** 是否必填（仅前端表单模式拦截 + 管理台账展示，不加服务端校验） */
    boolean required() default false;

    /** select/multiselect 的候选，常量枚举场景 */
    Option[] options() default {};

    /** 占位提示 */
    String placeholder() default "";

    /** 字段表达式角色：DATA 要数据（{{ }} 求值）/ TARGET 起名字（裸路径）/ LITERAL 普通字面量（缺省） */
    ExprRole exprRole() default ExprRole.LITERAL;

    /** 条件显示，简单 DSL：eq/ne/in/notIn；多条为 AND；field 支持点路径（相对当前对象层级） */
    ShowWhen[] showWhen() default {};

    /** 仅 KEY_VALUE_MAP 用：Map 值的表达式角色（httpRequest headers/query、dataPatch.patch 标 DATA） */
    ExprRole valueExprRole() default ExprRole.LITERAL;

    /** 排序，缺省按字段声明顺序（JDK 下 getDeclaredFields 稳定为声明序，关键字段可显式保险） */
    int order() default Integer.MAX_VALUE;
}

public @interface Option { String label(); String value(); }
public @interface ShowWhen { String field(); String eq() default ""; String ne() default "";
                            String[] in() default {}; String[] notIn() default {}; }
```

`WidgetKind`（最终实施值，12 个业务控件 + AUTO 哨兵）：
`AUTO / TEXT / TEXTAREA / NUMBER / BOOLEAN / SELECT / MULTISELECT / PASSWORD / CONNECTION_SELECT / KEY_VALUE_MAP / OBJECT_ROWS / OBJECT / JSON`

> 实施补全（2026-10-02）：原稿写「十种」漏了 OBJECT，但 §3.4「嵌套 Bean 折叠 object 分组」
> 与 §6.2「OBJECT 嵌套字段组」都需要它；落地时补齐 OBJECT（单 Bean 折叠分组），与
> OBJECT_ROWS（List&lt;Bean&gt; 数组行编辑）区分。两者都用 `fields` 承载递归子字段。

> 实施补全（2026-10-02 推广批）：再增 `CONNECTION_SELECT`（领域控件，对应 §6.1 的 ConnectionSelect）。
> 它**不参与类型推断**，只能对 String 类型的连接键字段显式标注（12 个 BPM/rds 件的 connectionId）；
> 值为连接**业务键** `connectionId` 字符串（如 `bpm-default`），不是 DB 主键 id——前端下拉
> 拉 §5.3 的 `/connection/options`，option value 必须是业务键，与 Cfg 存量数据口径一致。
> 纯增量枚举，旧 schema 不出现该值，老物料不受影响。

说明：

- **没有独立 TAGS 控件**：`List<String>`（如 responseHeaders、sub、addFields）推断为 MULTISELECT
  语义不合适（无固定候选），落地为 widget=MULTISELECT 但 options 为空时前端渲染自由输入标签串
  （推广批实现为 el-select multiple + filterable + allow-create，回车即添加标签，未单独封装 TagsInput）；
  也可显式 `widget = MULTISELECT` 配 options（如未来固定枚举集合）。
  控件映射细节前端统一处理，后端只描述「多选/标签」意图。
- `exprRole` 与 project_memory 字段角色铁律同源：DATA 字段渲染 ExprInput，TARGET 字段渲染 PathInput。
  httpRequest.url 这种「字面量中嵌表达式」也是 DATA（resolveEmbedded 语义）。
- Object 类型字段（value/body/sql/args 等）推断为 JSON；setValue.value 这种高频件显式标注：
  `@DatabusProp(widget = WidgetKind.JSON, exprRole = DATA, label = "写入值")`，
  前端对「JSON + DATA」组合渲染 ExprValue 控件（§6.4）。
- **showWhen 只用结构化 DSL，不引入表达式引擎（2026-10-02 拍板）**：条件显隐在浏览器端
  SchemaField computed 中求值（无网络、毫秒级），QLExpress 等服务端 Java 表达式引擎物理上
  不可用；字符串表达式还会让注解退化为无编译期校验、前端多引解析器与注入面。批 4 全部真实
  用例仅枚举字段 eq/in（无 OR/数值比较/跨字段计算，n8n displayOptions 同域亦如此）。
  表达力增强走纯增量路径：先给注解加 `gt/lt` 等操作符（JSON 加可空键，旧 schema 无感），
  仍不够再评估 JSONLogic（前后端均有实现）——不引入服务端表达式语言。

### 3.3 试点件 Cfg 注解完整示例

```java
@Data
public class SetValueCfg {

    @DatabusProp(
        label = "写入路径", required = true, exprRole = ExprRole.TARGET, order = 1,
        placeholder = "$.setValue1.userName",
        description = "写入目标 JSONPath（父路径不存在自动创建），裸路径不求值"
    )
    private String path;

    @DatabusProp(
        label = "写入值", exprRole = ExprRole.DATA, widget = WidgetKind.JSON, order = 2,
        placeholder = "常量 或 {{ $.入参路径 }}",
        description = "字面量直接写入；动态值写 {{ $.路径 }}，整字段求值保留原类型。数字/布尔/对象字面量请用 JSON 高级模式"
    )
    private Object value;
}
```

### 3.4 无注解时的类型推断兜底

不打 `@DatabusProp` 也能渲染（满足「反射带 Java 类型默认值」）：

| Java 类型 | 推断 widget | 备注 |
| --- | --- | --- |
| String | TEXT | 多行文本（script/sql 等）显式 TEXTAREA |
| Boolean / boolean | BOOLEAN | |
| Integer/Long/Double/Short/Float/BigDecimal/BigInteger | NUMBER | |
| 枚举（本项目无，预留） | SELECT | options 取枚举常量 |
| List<String> / Set<String> | MULTISELECT（无 options → 标签串） | |
| List<Bean> | OBJECT_ROWS | 元素 Bean 字段递归成列 |
| Map<String, ?> | KEY_VALUE_MAP | |
| 嵌套 Bean（静态内部类） | 折叠 object 分组，字段递归 | el-collapse 嵌套 |
| Object / 其他 | JSON（逃生舱） | |

泛型元素类型通过 `ParameterizedType` 解析（`List<Mapping>` → Mapping、`Map<String, Object>` → value 为 Object）；
解析不出元素类型的裸 Collection/Map 兜底 JSON。同一组件内嵌套 Bean 复用、递归深度不限（最深实测为 3 层：
boCreate.boList[].rewrite.addFields）。

### 3.5 脚本两物料的特殊注册

script / booleanScript 不是 Spring bean，扫描器扫不到。在 scanner 中维护一个**内置补充清单**
（常量代码，不造新注解）：

```java
// 伪码：registerSupplement("script", "脚本", NodeTypeKind.NODE, ScriptCfg.class, ...)
//      registerSupplement("booleanScript", "条件脚本", NodeTypeKind.BOOLEAN, ScriptCfg.class, ...)
```

schema 顶层加标记 `editor: "script"`；前端 CmpProps 的脚本专用分支继续保留，SchemaForm 不对它们渲染。
脚本两物料出现在 /options 与管理台账里（元信息完整），只是配置区不走通用表单。

### 3.6 Cfg 与 Component 的文件/分包约定（2026-10-02 拍板）

普查后确立的硬约定（第一批推广件落地时全量整改，22 个 Cfg 已归位）：

1. **Cfg 必须是独立 .java 文件的顶层类，不得内嵌进配对 Component**。两者生命周期根本不同：
   Component 是 Spring 单例无状态执行器（全链路共享一个 bean）；Cfg 是 `process()` 里
   `getCmpData(XxxCfg.class)` **每次执行现场反序列化**的请求级对象，Jackson new 出的对象不在
   Spring 容器里，挂在 Component 自身字段上必然多流程并发互相覆盖。
2. **全部 Cfg 统一住扁平包 `org.dromara.databus.component.cfg`**（共 22 个：21 个业务件 Cfg——
   20 个已打 @DatabusCmp 加未迁移的 SwitchRouteCfg——再加脚本共用的 ScriptCfg）；
   Component 按业务域分包：`bpm / data / flow / logical / protocol`。
   规则一句话可校验：Cfg 只能住 cfg 包。Cfg 类名天然唯一（XxxCfg），不再按域拆子包。
3. **Cfg 自己的静态内部类允许内嵌**：如 BoCreateCfg.BoItemCfg/RewriteCfg、BoQueryCfg.MainCfg/RelateCfg、
   HttpRequestCfg.AuthCfg/MappingCfg——它们是同一配置结构的组成部分，随外层 Cfg 一起反射与反序列化。
4. **唯一例外是第二步脚本原子**（§5.5.5）：脚本文本必须自包含，Cfg 固定内嵌在原子类里；
   本节约束只针对内置 Java 件。
5. 本约定与数据库无关：EL 拓扑里节点 code 与节点 data JSON 本就是两个存储字段
   （code 决定捞哪个 bean，data 是 Cfg 的值），合并不可能也无收益；本次只整理 Java 物理布局，零 DDL。

---

## 4. 后端：反射扫描与注册中心

### 4.1 ComponentSchemaScanner

- 实现 `ApplicationRunner`（容器启动后执行一次），注入 `ApplicationContext`；
- `context.getBeansOfType(DatabusNodeComponent.class)` 拿全部组件 bean（含未来 jar 扩展件，只要被 Spring 扫描到）；
- 逐类读 `@DatabusCmp`：缺注解的 bean 不进 schema（v1 阶段允许逐步补注解；未补的件前端继续走 JSON 编辑器）；
- code 冲突（同 code 两条）启动 fail-fast 抛异常，列冲突类名；
- 反射 Cfg 类生成 `CmpSchema`，结果不可变存入注册中心；再追加 §3.5 脚本补充清单。

### 4.2 反射规则细则

- 字段集＝`getDeclaredFields()` 全字段（private 含），不分父类（Cfg 当前无继承；未来如有再向上一层）；
- 过滤：`static`、`transient`、`@JsonIgnore` 字段跳过；`serialVersionUID` 自然跳过；
- 顺序：显式 order 优先，其余按声明序；
- Label：注解 label → 字段名；description 取注解（**Javadoc 不进字节码，运行期不可读，不依赖注释**）；
- 嵌套 Bean 内字段同样支持 `@DatabusProp`（递归处理，showWhen 的 field 相对各自对象层级）；
- 输出模型用 record 或不可变 POJO，带 Jackson 注解序列化为 §5 的 JSON 契约。

### 4.3 ComponentSchemaRegistry

```java
public interface ComponentSchemaRegistry {
    /** code → schema；未打注解的件返回 null（前端回退 JSON 编辑器） */
    CmpSchema get(String code);
    /** 全部内置 schema（含脚本补充件），按 sort 排序 */
    Collection<CmpSchema> all();
}
```

纯内存、无缓存 TTL（注解随 jar 版本走，重启即刷）；不查库、不调网络。

---

## 5. 后端：/options 合流接口

### 5.1 Schema JSON 契约

```json
{
  "code": "setValue",
  "name": "赋值",
  "shortName": "",
  "group": "business",
  "icon": "ph:pencil-simple",
  "color": "#67c23a",
  "description": "把值（常量或 {{ $.路径 }} 表达式取值）写入上下文 $.数据空间.path",
  "nodeType": "NODE",
  "editor": "form",
  "source": "SYSTEM",
  "sort": 10,
  "schema": {
    "fields": [
      { "name": "path", "label": "写入路径", "widget": "TEXT", "exprRole": "TARGET",
        "required": true, "placeholder": "$.setValue1.userName", "order": 1,
        "description": "写入目标 JSONPath（父路径不存在自动创建），裸路径不求值" },
      { "name": "value", "label": "写入值", "widget": "JSON", "exprRole": "DATA",
        "required": false, "order": 2,
        "description": "字面量直接写入；动态值写 {{ $.路径 }}，整字段求值保留原类型" }
    ]
  }
}
```

字段对象（PropSchema）完整键：

| 键 | 说明 |
| --- | --- |
| name / label / description / placeholder / order | 基础元信息 |
| widget | 12 种业务控件之一（不出现 AUTO，反射阶段已解析为最终控件；CONNECTION_SELECT 仅显式标注，见 §3.2） |
| required | 表单必填 |
| exprRole | DATA / TARGET / LITERAL |
| options | `[{label,value}]`，select 候选 |
| showWhen | `[{field, eq/ne/in/notIn}]`，AND |
| fields | OBJECT_ROWS：元素 Bean 的字段 PropSchema[]；嵌套 object：子字段 PropSchema[] |
| keyWidget / valueWidget | KEY_VALUE_MAP 可选，缺省 key=TEXT、value=JSON（http headers 值允许表达式时 valueExprRole=DATA） |
| valueExprRole | KEY_VALUE_MAP 值的表达式角色（httpRequest headers/query、dataPatch.patch 都是 DATA） |

顶层 `editor`：`form`（SchemaForm 可渲染）/ `script`（脚本专用编辑器）。
`schemaVersion: 1` 放在响应包络级（数组外层包一个对象或响应头），应对契约演进。

### 5.2 合流与冲突策略

改造 `DatabusComponentServiceImpl.queryEnabledList()`：

1. registry.all() 取内置件 → VO，`source=SYSTEM`；
2. 查 databus_component 启用行 → 解析 param_schema 列（同构 JSON）→ VO，`source=CUSTOM`；
3. **code 冲突：内置优先，DB 行丢弃并记 warn 日志**（自定义件不允许占用内置注册名；第二步脚本原子注册时也会做 code 唯一校验，已有 uk_component_code 索引只管表内）；
4. 按 sort（内置）/ id（自定义）排序返回。

VO 新增字段：`source`、`nodeType`、`editor`、`color`、`shortName`、`sort`、`schema`（对象，非字符串）。
旧字段（componentName/category/paramSchema 蛇形串）保留给组件管理页既有 CRUD，/options 可同时冗余或新 VO 二选一——
实施时新建 `ComponentOptionVo`，不复用 DatabusComponentVo，避免两套口径混在一个类。

权限沿用 `databus:editor:list`（编辑器用户都有），无新增菜单权限。

### 5.3 配套：连接轻量选项接口

connectionSelect 控件需要数据源。新增：

- `GET /databus/connection/options`：只返回启用连接（不分页、不回 endpoint/密钥），
  权限挂 `databus:editor:list`（编辑器配置者需要看得到连接，不要求有连接管理权限）；
- **实施补全（2026-10-02）实际契约为 `ConnectionOptionVo(Long id, String connectionId, String connectionName, String connectorType)`**：
  类型字段定稿名 `connectorType`（原稿 connType 作废）；前端 option label 拼
  「connectionName（connectorType）」，**option value 用业务键 `connectionId`（如 `bpm-default`），
  不用 DB 主键 id**——Cfg 存量数据存的就是业务键。试点期曾只返回 id/name/type 三字段，推广批补齐 connectionId；
- 前端在既有 `src/api/databus/connection/` 内追加 `listConnectionOptions()`，ConnectionSelect 带模块级
  pending 单飞缓存（多个连接字段同时渲染只发一次请求），失败显空态提示可重试。

### 5.4 组件管理页只读台账

- 新端点 `GET /databus/component/registry`：返回与 /options 同源全量（含停用的自定义行，source/status 完整），
  供管理页；权限 `databus:component:list`。
- 管理页本期能力：**只读列表**（code/name/分组/source 标签/描述/排序）+ 点行抽屉看 schema 美化 JSON；
  内置行不可编辑不可删（按钮禁用 + tooltip「内置组件」）；自定义行的维护（增删改 param_schema）
  随第二步脚本原子宿主一起做，本期表上新增/编辑按钮可保留但不管 schema 可视化。
- 现有 `/list`、CRUD 端点不动（只查 DB 表，继续为自定义行管理服务）。

### 5.5 第二步脚本原子的 schema 来源（2026-10-02 补充拍板：javax-pro + 同套注解）

本节把正本第二步「脚本 schema 从哪来」提前钉死，保证第一步不返工。

#### 5.5.1 结论

1. **注册式脚本原子的语言定为 Java（`liteflow-script-javax-pro`，Liquor 内存 javac），不用 Groovy。**
2. 原子脚本文本＝一段完整 Java 类源码：外层类 `extends ScriptAtomComponent`（平台脚本原子基类，
   本身是 `DatabusNodeComponent` 子类，组件结构正本见 [databus-ai-atom-material.md §2.5](databus-ai-atom-material.md)）
   打**同一个** `@DatabusCmp`，内嵌静态 `Cfg` 类的字段打**同一个** `@DatabusProp`，
   取参/数据空间/摘要全部复用基类——与 Java 组件同构，脚本文本自包含逻辑与 schema，不另搞 frontmatter / 外挂 JSON。
3. **一套注解模型、一个反射器、一份 PropSchema 契约**：第一步的内省器设计为
   `introspect(Class<?> cfgClass)` 纯函数，Java bean 扫描与第二步脚本编译产物两个入口共用；
   `/options` 合流、前端 SchemaForm、cmpData 存储全部零改动。
4. Groovy 保留给画布上的自由脚本节点（script / booleanScript，引擎下拉不动），不承载注册式原子。

#### 5.5.2 为什么 Groovy 路径不通（本地实跑，非臆测）

- Groovy 库存脚本是 `Script` 子类的一段执行体，**脚本顶层不允许 static 字段**
  （Groovy 3.0.25 实跑：`static meta = [...]` 直接编译错 `Modifier 'static' not allowed here`），
  原本设想的「static META 反射」在第一步就被否掉。
- 在执行体内声明普通嵌套类 + Java 注解虽可编译，但反射取注解要 `parseClass → loadClass → get`，
  实跑探针确认：**读 static 字段会触发该类 `<clinit>`，恶意 static 块能在「读 schema 阶段」执行任意代码**。
- 「只编译到 AST 不加载」能零执行，但 AST 只能取常量字面量，带表达式的注解/默认值取不到，
  约束多到要为脚本单造一套描述语言——比写 Java 类还贵。

#### 5.5.3 javax-pro 可行性证据（2026-10-02 实跑）

环境：JDK 21（temurin）+ `liteflow-script-javax-pro:2.16.3.1` + Liquor 1.6.6 + **真实 `liteflow-core:2.16.3.1`**。

1. Liquor `DynamicCompiler` 内存编译「外层类 `extends NodeComponent` + 内嵌 `Cfg`（字段标 RUNTIME 保留的
   `@DatabusProp`）+ `process()` 调 `this.getCmpData(Cfg.class)`」**一次通过**，产物含 `Outer` 与 `Outer$Cfg` 两个类。
2. 编译后只 `loadClass("Outer$Cfg")` 反射字段与注解，探针在两处 static 块、外层构造器、`process()` 里埋的标记
   **全部没有输出**——javac 只编译不执行；读注解不触发类初始化（与 Groovy static 反射形成关键对比）。
3. 无注解字段同样可反射 → §3.4 类型推断兜底对脚本 Cfg 原样成立。
4. 语法残缺源码 `compile()` 即抛 `DynamicCompilerException`，`getErrors()` 返回带行号诊断
   （实测中文：「进行语法分析时已到达文件结尾」）→ **保存即拦截**有直接 API 依据。
5. javax-pro 自身机制（字节码核实 `JavaxProExecutor`）：`convertScript` 把类源码包成
   `X item = new X(); return item;` 交 `Scripts.compile`。**注意区分两条通道**：
   低层 `DynamicCompiler` 只编译不 exec（我们保存期取 schema 走它，零实例化）；
   但框架 `JavaxProExecutor.validate()/load()` 走 `Scripts.eval` 会真的 `new X()`——
   构造器/字段初始化/static 块此时执行（2026-10-02 源码+实跑复核，§5.5.6 已按此修正）。
6. **2026-10-02 真·LiteFlow 链路实跑补充**（FlowExecutor 全链路，非单机构造）：
   脚本 extends 平台抽象基类（基类 `final process()` + 脚本实现 `abstract doProcess()`）执行成功；
   编译产物单例跨节点复用，getCmpData/getTag/getNodeId 每轮现场正确；
   异常翻译有剥壳陷阱须用无 cause 异常（详见正本 §2.5.2/§2.5.7）；
   运行期 executor.load 同名类热更生效。

#### 5.5.4 业界对标

- **Windmill**：TS/Python/Go 脚本从强类型函数签名反推参数 JSON Schema——本质是「类型系统即 schema」。
  javax-pro 让 JVM 侧拿到同一件东西：强类型 Cfg 字段 → 反射出 schema，维护者零手写第二份描述。
- **Pipedream**：组件代码内声明 props（code-defined），方向一致；但 JS 字段无静态类型，类型仍须手写。
- **GitHub Actions action.yml / frontmatter 派**：适合无类型宿主（YAML/JS）。我们有强类型 Java 宿主，
  再外挂一份 YAML/JSON 是自废武功且制造两处漂移，明确不采用。

#### 5.5.5 原子脚本标准结构（AI 产出与手工编写的唯一模板）

```java
import org.dromara.databus.component.script.ScriptAtomComponent;
import org.dromara.databus.component.schema.annotation.DatabusCmp;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

@DatabusCmp(
    code = "mySetValue",                 // 注册身份；入库时与 databus_component / lf_script 的 nodeId 对齐
    name = "我的赋值", icon = "ph:pencil-simple", color = "#67c23a", group = "business",
    description = "把常量或表达式取值写入指定路径"
    // 脚本版不声明 cfg()：内嵌配置类固定命名 Cfg（见下）
)
public class MySetValueAtom extends ScriptAtomComponent {

    public static class Cfg {
        @DatabusProp(label = "写入路径", required = true, exprRole = ExprRole.TARGET,
                     placeholder = "$.mySetValue1.userName")
        private String path;

        @DatabusProp(label = "写入值", widget = WidgetKind.JSON, exprRole = ExprRole.DATA)
        private Object value;
    }

    // 拍板 A（模板式）：只实现 doProcess()；process() 基类 final（异常翻译/摘要兜底），禁止覆写
    @Override
    protected void doProcess() {
        Cfg cfg = getCmpData(Cfg.class);          // 基类能力：cmpData 反序列化
        save(cfg.getPath(), resolveParam(cfg.getValue()));  // 基类能力：数据空间 + {{ }} 预解析
        resultSummary("赋值：" + cfg.getPath());
    }
}
```

约定：

- 外层类名自由（`convertScript` 正则要求类声明必须带 `extends/implements` 关键字，裸 class 会报
  "cannot find class defined"），内嵌配置类**固定名 `Cfg`**，省掉脚本版注解再引一个 `Class` 属性；
- **必须保留默认无参构造器**（框架固定生成 `new X()`，禁止写带参/显式构造器）；禁止实例初始化块、
  static 块、非常量实例字段初始化；**禁止实例字段承载执行状态**——脚本实例是跨执行/跨链路共享的
  单例，执行现场由框架每轮注入，可变状态一律走上下文数据空间（2026-10-02 真链路实跑：同单例
  两轮不同 data/tag 互不串台靠的就是 refNode 注入，实例字段若存值必并发踩坏）；
- 异常一律直接抛或由基类 final process 翻译，**不要自己 try/catch 再包一层带 cause 的异常**
  （会被 javax-pro 剥壳，详见正本 §2.5.2）；
- `@DatabusCmp`/`@DatabusProp` 与枚举来自平台主 jar，对脚本编译类路径天然可见（父 ClassLoader 即平台加载器）；
- 行编辑、嵌套、KV、showWhen 等全部能力在脚本 Cfg 上可用——它们只是 Java 字段 + 注解，没有第二种玩法。

#### 5.5.6 保存 / 发布管线（第二步实施，本节定契约）

```
脚本文本保存（databus_component 草稿行；脚本文本草稿存储第二步细化）
   ├─ ScriptAtomCompiler（自研）：Liquor DynamicCompiler 编译，★不调框架 ScriptValidator
   │    （框架 validate 走 Scripts.eval 会 new 实例，构造器/字段初始化/static 块即执行——
   │     保存阶段必须零实例化，把任意代码执行窗口挡在人审之前）
   │    失败 → getErrors() 行号诊断直接返回前端，保存被拒
   ├─ loadClass 反射外层 @DatabusCmp + Outer$Cfg（不触发类初始化）→ introspect(Class) → PropSchema JSON
   └─ 写入 databus_component.param_schema（source=CUSTOM，状态=草稿）
人审 → 发布物料：
   ├─ lf_script 写 nodeId=code / script_language=java / script_type=script
   │    （表零改动：2.16.3.1 DDL 已含这两列；RulePublishService 按 ScriptNodeSpec 透传 language）
   ├─ databus_component 状态=启用；/options 即刻可见
   └─ 框架加载时 new 一次单例（构造器首次执行，已在人审后；模板只允许常量级初始化）
改脚本：同一管线重跑 → content_md5 冲突校验 → executor.load 热替换（同名类即生效，已实跑）
        schema 随文本刷新——schema 与逻辑同处一份文本，物理上不可能两处漂移
```

#### 5.5.7 安全边界（不吹沙箱）

- javax-pro 是全功能 Java，**没有 Groovy `SecureASTCustomizer` 的等价物**：`java.lang.*` 隐式可见，
  `Runtime`/`ProcessBuilder` 不靠 import 白名单挡得住；JDK 17 起 SecurityManager 已弃用。
- v1 威胁模型：原子作者＝持权限点的内部实施/顾问（可信账号），且**保存草稿 → 人审闸门 → 才能发布**
  （正本已有的 AI 人审铁律同样管人手写）；执行环境是客户内网。沙箱与句柄注入未落地前不开放给外部作者。
- 加固路线（出现外部作者需求才立项）：编译后 defineClass 前做 ASM 字节码黑名单扫描
  （禁直调 Runtime/ProcessBuilder/System.exit/反射/文件/网络，强制走注入句柄）；再不够上独立工作进程。
  注：SecureASTCustomizer 历史上也有绕过 CVE，从来不是硬沙箱承诺。
- 正本 §2.3 第 1 条「Groovy + SecureASTCustomizer」按本节调整：**注册式原子走 Java，管控＝人审 + 发布闸门 +
  后续字节码黑名单**；Groovy 仅存于画布自由脚本节点，其无沙箱红项维持 [databus-script-component.md §7](databus-script-component.md) 口径。

#### 5.5.8 环境与依赖注意

- 新增 `com.yomahub:liteflow-script-javax-pro:2.16.3.1`（传递 `org.noear:liquor-eval:1.6.6` → `liquor`）。
  **阿里云公共镜像 2026-10-02 只同步到 2.16.1.2，Maven Central 有 2.16.3.1**——实施时需放开 central
  或传私服；版本必须 ≥2.16.1.1（修 ThreadLocal 泄漏，见 LiteFlow 技能 scripts.md §三）。
- 部署机必须 **JDK 不能是 JRE**（Liquor 依赖 `javax.tools.JavaCompiler`）；现网 JDK 21 满足。
- 编译为百毫秒级，只发生在保存/发布与启动预编译；执行期走缓存，性能与 Java 件同量级。

#### 5.5.9 边界与第二步开工实测项

- 注册式原子只有 `type=script`（业务处理叶子）；布尔/选择/循环不做注册式原子，维持 Java 件与 EL 算子现状。
- 2026-10-02 真链路实跑已销项：①getCmpData 嵌套类反序列化（与 Java 件同通道，两轮 data 正确）；
  ②lf_script 语言透传为源码级核实（DDL 含列 + ScriptCandidateLoader 透传），executor.load 热替换实跑通过；
  ③final process 模板、异常无 cause 翻译、单例现场注入。
- 第二步在项目工程内仍需实测：
  1. Spring Boot fat jar（LaunchedURLClassLoader）下 ScriptAtomCompiler 编译可见性与 TCCL 时序，
     不稳则显式传平台 ClassLoader + 启动后预热（正本 §2.5.7 同款）；
  2. Rule-DB 写 lf_script 后端到端生效（3s 轮询/60s 对账/last-good）与项目执行记录 LifeCycle 钩子
     对 javax-pro 节点的摘要采集；
  3. 平台注解默认值/动态 options 在编译反射通道下的 schema 完整度（第一步先由 SetValue 形态①兜底验证）。
- 官网文档口径备注：liteflow.cc 公开页面树当前仍挂 v2.11.X（Java 脚本页讲的是旧插件
  `liteflow-script-java` 的 Janino 接口写法，与 javax-pro 类式写法是两个插件，勿混用）；
  2.16 文档源（gitee 文档仓库 `04.v2.16.X文档/100.脚本组件`）的 javax-pro 断言本次已全部用
  2.16.3.1 官方 sources jar 源码 + 真链路实跑独立复核，技能 scripts.md 与之一致。

---

## 6. 前端：SchemaForm 设计

### 6.1 文件规划（全部新增，CmpProps 只做一处分支接入）

```
src/api/databus/component/
  index.ts            # listComponentOptions() 等
  types.ts            # ComponentOption / PropSchema / Widget / ExprRole 类型
src/views/databus/editor/
  composables/
    useComponentOptions.ts   # 拉 /options + 模块级 Promise 缓存（编辑器会话只拉一次）
  components/schema-form/
    SchemaForm.vue           # 递归表单（v-model 绑定配置【对象】）
    SchemaField.vue          # 单字段：OBJECT 分组递归 / OBJECT_ROWS / KEY_VALUE_MAP 容器分发，其余交 FieldControl
    FieldControl.vue        # 纯标量控件分发（无 label/form-item/showWhen），SchemaField 与行编辑单元格共用
    widgets/
      ExprInput.vue          # DATA 角色：文本输入 + 「插入 {{ $.路径 }}」按钮 + 提示
      PathInput.vue          # TARGET 角色：裸路径输入 + $. 前缀提示
      ExprValueEditor.vue    # JSON+DATA：字符串态表达式/字面量；复杂类型转 JSON 高级模式
      ConnectionSelect.vue   # 领域控件：拉 /connection/options（模块级单飞缓存，value=connectionId）
      KeyValueMapEditor.vue  # KV 行编辑（值控件按 valueWidget/valueExprRole，复用 FieldControl）
      ObjectRowsEditor.vue   # 对象数组行编辑（el-table，列＝fields；OBJECT 列点按钮弹 popover 内嵌 SchemaField）
```

> 实施补全（2026-10-02 推广批）：新增 FieldControl 作为标量控件唯一分发点，ObjectRowsEditor 的单元格
> 直接复用它，避免行内单元格再包一层 SchemaField（label/显隐在表格里无意义）。原稿规划的独立
> TagsInput.vue **未单独建文件**：MULTISELECT 无 options 的自由标签串场景由 FieldControl 内
> `el-select multiple filterable allow-create default-first-option` 直接承接（List<String> 标签输入）。

### 6.2 SchemaForm 契约与递归

```vue
<!-- v-model 是解析后的配置对象，不是 JSON 字符串 -->
<SchemaForm :schema="cmpOption.schema" v-model="formModel" :disabled="..." />
```

- SchemaForm 按 fields 顺序输出 el-form-item（label-position=top，与 CmpProps 现有风格一致）；
- OBJECT 嵌套字段组：el-collapse（默认展开第一层）或带标题的缩进分组，v1 用轻量分组（标题 + 边框，不折叠也行——实施时先分组不折叠，超屏再折叠）；
- OBJECT_ROWS：ObjectRowsEditor 接 `fields`（元素 Bean 的 PropSchema[]），每行一个对象，增删行、标量列
  单元格直接复用 FieldControl；**元素里再嵌 OBJECT 列（如 boCreate.boList[].rewrite）不在行内塞分组表单
  （表格放不下），改为「设置/编辑策略」按钮 + el-popover 弹层，层内递归 SchemaField，点开时确保行内嵌套
  对象已预建**；顶层 OBJECT 字段（如 boQuery.main）仍走正常分组，不经过弹层；
- KEY_VALUE_MAP：key 文本 + value 控件（默认 ExprInput 或 JSON，按 valueExprRole/valueWidget）；
- showWhen：SchemaField 内 computed 判断当前模型值（field 支持点路径，从所在对象根解析），不命中 v-if=false 且**不清理已填值**（切回来还在；与 n8n displayOptions 行为一致）；
- required：el-form-item 标红星，blur/变更时即时错误文案；保存/试运行时由 CmpProps 现有提交流统一拦截（v1 在 SchemaForm 暴露 validate() 方法，CmpProps 保存画布前调用）。

### 6.3 双模式 tab 与坏态保留（学 InputParamsDialog 已验证模式）

CmpProps「其他业务组件」分支的配置区改为：

```
el-radio-group（表单模式 / JSON 高级模式），仅当该 code 有 schema（editor=form）时显示
├ 表单模式：SchemaForm（模型＝内存对象 formModel）
└ JSON 模式：JsonCodeEditor（文本＝jsonText）
```

同步规则（与入参登记弹窗左右双栏同一套纪律）：

1. 切节点：parse ElNode.data；parse 成功同时初始化 formModel 与 jsonText；**parse 失败：默认进 JSON 模式报错，formModel 保留上一节点不串值**；
2. 表单改动 → 立刻 `jsonText = JSON.stringify(formModel)`（不要求格式化，切 JSON 时 JsonCodeEditor 现有自动格式化会美化）；
3. JSON 改动（blur）：parse 成功才覆盖 formModel；失败保留旧 formModel，状态栏显示 JSON 不合法，禁止切回表单模式？——不禁止，但切表单时用旧对象 + Message 提示「JSON 有语法错误，表单显示上次有效内容」；
4. 最终写回 ElNode.data 的源头：两个模式都写（表单改产出 JSON 串、JSON 改产出文本），保持现有 `treeModel.updateLeafData(id,{data})` 单一通道。

### 6.4 ExprValue 控件（Object + DATA 字段的务实边界）

setValue.value / condition.value / response.result 这类字段既可能是字符串字面量、`{{ }}` 表达式，
也可能是数字/布尔/对象。单一控件无法优雅覆盖全部，明确边界：

- 主形态＝单行输入（ExprInput）：值是字符串；以 `{{` 开头按表达式处理；其余按字符串字面量——这覆盖 80% 配置；
- 用户要写数字 `200`、布尔 `true`、数组/对象字面量时，用 JSON 高级模式写；
- 控件内不做「200 自动转 number」魔法（switchRoute 现有手写表单的数字串转换是特例，不泛化，避免字符串"200"被误转）；
- placeholder/description 写清这条边界。

### 6.5 表达式插入按钮（v1 最小版）

ExprInput/ExprValue 右侧一个 `{ }` 按钮，点击在光标处插入 `{{ $.路径 }}` 占位并选中「路径」文字。
路径自动联想（从当前画布数据空间树生成候选）记 backlog，v1 不做。

### 6.6 CmpProps 接入：试点白名单 → 全量 schema 驱动

试点期 CmpProps 曾有硬白名单：

```ts
/** 第一批接入 SchemaForm 的物料（灰度白名单，随批次逐步放开） */
const SCHEMA_FORM_CODES = ['setValue'] as const;
```

「数据空间 + JSON」分支中，当 `componentCode` 命中白名单且 options 里存在 editor=form 的 schema，
渲染双模式配置区；其余物料一行不动，继续 JsonCodeEditor。
数据空间名、节点标题、更换条件件、删除节点等周边逻辑零改动。

> **实施补全（2026-10-02 推广批）：白名单已拆除，改纯 schema 驱动。** 启用判据只剩
> `activeOption.editor === 'form' && schema.fields.length > 0`（switchRoute 显式排除），
> 后端给 schema 就渲染表单，前端不再维护任何物料 code 列表：
>
> - condition/forLoop/iteratorLoop 的旧手写结构化表单（loopForm/STRUCTURED_CODES 等）**整块删除单轨**，
>   旧正则校验/提示文案由注解 label/description/options/showWhen 承接；存量 data JSON 打开即自动回显表单；
> - **switchRoute 手写表单原样保留**（cases.target 吃画布 case 名，§8 不迁移项），独立分支
>   `isSwitchRouteLeaf`，本地状态改名 switchForm，不再与循环件共用 loopForm；
> - script/booleanScript 脚本专用编辑器不动；
> - options 异步返回后 watch 到当前节点变可用时补一次表单同步，避免先渲染空 JSON 表单。

---

## 7. setValue 试点实施清单

1. ~~后端：schema 包四组类（2 注解 + 3 枚举 + 2 模型 + scanner + registry）~~【已实施 2026-10-02：
   实际为 component/schema 下 annotation（@DatabusCmp/@DatabusProp，Option/ShowWhen 内嵌）、
   enums（WidgetKind/ExprRole/NodeTypeKind/EditorKind）、model（CmpSchema/PropSchema/OptionVo/ShowWhenVo）、
   introspect（CfgIntrospector 无 Spring 纯函数）、registry、scanner；mvnw compile + IDE 诊断通过】；
2. ~~后端：SetValueComponent 加 @DatabusCmp、SetValueCfg 两字段加 @DatabusProp（§3.3）~~【已实施 2026-10-02】；
3. ~~后端：ComponentOptionVo + service 合流 + `/options` 改造 + `/connection/options` 新端点~~【已实施 2026-10-02：
   domain/vo 新增 ComponentSource/ComponentSchemaBody/ComponentOptionVo（ofSystem/ofCustom 双工厂，@JsonInclude NON_NULL）/
   ComponentOptionsVo（CURRENT_SCHEMA_VERSION=1）/ConnectionOptionVo；service 先 registry 映射 SYSTEM 再查 status='0' 自定义行，
   code 冲突丢弃+warn，param_schema 解析失败降级仅身份元信息；连接选项查 enabled='Y' 映射 id/connectionId/connectionName/connectorType（推广批补 connectionId 业务键，见 §5.3）】；
4. ~~前端：api/component + types + useComponentOptions~~【已实施 2026-10-02：
   api/databus/component（types 含 WidgetKind 12 值（推广批加 CONNECTION_SELECT）/ExprRole/PropSchema/ComponentOption 等 + listComponentOptions）、
   connection 追加 ConnectionOption/listConnectionOptions；useComponentOptions 模块级 Promise 缓存，失败释放可重试】；
6. ~~前端：SchemaForm/SchemaField + TEXT/JSON/BOOLEAN/ExprInput/PathInput/ExprValue（试点最小控件集）~~【已实施 2026-10-02：
   SchemaForm（label-position=top + required 点路径校验 + validate/clearValidate 暴露）、
   SchemaField（widget 分发 + exprRole 叠加 + showWhen eq/ne/in/notIn 多条 AND + OBJECT 分组递归，
   OBJECT 子对象由 CmpProps 同步时预建，computed 零副作用）、widgets/ExprInput（{ } 按钮插入 {{ $.路径 }} 并选中路径）、
   PathInput、ExprValueEditor（字符串态走 ExprInput，复杂类型只读提示去 JSON 高级模式，不做自动转型）】；
7. ~~前端：CmpProps 双模式 tab + 白名单~~【已实施 2026-10-02：
   SCHEMA_FORM_CODES=['setValue'] 硬白名单；表单模式/JSON 高级模式 radio；§6.3 同步纪律落地——
   切节点合法 JSON 同时初始化 formModel/dataStr，坏/非对象 JSON 默认 JSON 模式且 formModel 保留上节点内容，
   表单改动即 JSON.stringify 走 updateLeafData 单通道，JSON blur 合法才回灌表单；其余物料零改动】；
8. ~~静态校验：mvnw compile、oxlint、vue-tsc、IDE 诊断四绿~~【2026-10-02 通过：
   mvnw -pl ruoyi-modules/ruoyi-databus -am compile -o、oxlint 0、vue-tsc 仅剩既存 monitor/logininfo 大小写基线错误、IDE 诊断 0】。

**用户实测要点（试点通过标准）**：

- 新拖 setValue：表单模式填 path/value 保存 → 重开面板值回显一致；
- value 写 `{{ $.request.x }}` 试运行，取值与原 JSON 手填完全一致；value 写纯字符串、数字（JSON 模式）分别验证；
- path 必填拦截（前端红错 + 后端原抛错仍在）；
- JSON 高级模式与表单互切：合法互转、JSON 故意写坏时表单保留上次内容、节点 ElNode.data 始终合法或按现状原样保留；
- 打开一条旧链路里的 setValue（历史 JSON）回显正常——**零迁移验证**；
- 其他 20 个物料属性面板行为零变化（回归点 httpRequest 手填 JSON 试运行一条）。

> **2026-10-02 用户亲测通过，setValue 试点正式收线**：主链路（表单显示/`{{ }}` 取值试运行）+
> 四边界（坏 JSON 不覆盖表单且红字提示、path 必填红错、合法 JSON 回灌表单、script/httpRequest/forLoop 等其余物料面板零变化）全部通过。

---

## 8. 推广批次与实施结果（试点通过后）

原规划为 11 扁平 / 5 行编辑 / 3 复杂三批独立交付（下表分组保留作物料与形态索引）；
**2026-10-02 用户拍板「19 件一锅烩」**：setValue 试点亲测通过后，剩余 19 件一次迁移、一次实测，
不再分批。当日已完成：20 个 Java 件全部打齐 @DatabusCmp/@DatabusProp、白名单拆除（§6.6）、
condition/forLoop/iteratorLoop 旧手写表单单轨删除、CONNECTION_SELECT/valueExprRole 契约增量、
22 个 Cfg 归位 cfg 包（§3.6）；mvnw compile / oxlint / vue-tsc（仅剩既存基线错误）/ IDE 诊断四绿，
**待用户逐件实测收线**。

| 原批次 | 物料（19 件 + 试点 1 件，已全部实施） | 形态/新控件需求 |
| --- | --- | --- |
| 批 1 试点 | setValue | TEXT/JSON + DATA/TARGET |
| 批 2 扁平件（11） | condition、response、forLoop、iteratorLoop、sessionCreate、processStart、processTerminate、taskComplete、fileDownload、fileUpload、rdsExecute | SELECT(op/method 枚举)、BOOLEAN、NUMBER、PASSWORD、CONNECTION_SELECT；forLoop.count 走 JSON+DATA（ExprValue）；rdsExecute.sql 用普通 TEXT 透传（不嵌表达式）、args 无注解走 JSON 推断 |
| 批 3 行编辑件（5） | boUpdate、boDelete、idCardToUserId、fieldMap、boCreate | OBJECT_ROWS、嵌套 object（rewrite，行内 popover 编辑）、List<String> 标签串、select 枚举；fieldMap.to 为 TARGET |
| 批 4 复杂件（3） | boQuery、httpRequest、dataPatch | KEY_VALUE_MAP（valueExprRole=DATA）、嵌套 auth/main、mappings/relate 行编辑、showWhen（见下） |
| 不迁移 | script、booleanScript | 专用编辑器；schema 仅台账展示（editor=script） |
| 不迁移 | switchRoute | cases.target 候选是画布 case 名，组件自身 schema 无法表达；保留现有手写表单（不打 @DatabusCmp） |

> 注册数口径：scanner 实际注册 **22 个** schema = 20 个打注解的 Java 件 + 脚本补充 2 个；
> switchRoute 不注册（前端手写分支），23 个业务物料中它是唯一无 schema 表单的 Java 件。

showWhen 典型用例（已全部落注解）：

- httpRequest：`body` showWhen bodyType ne none；`rawContentType` showWhen bodyType eq raw；
  auth.username/password showWhen auth.type eq basic；auth.token showWhen auth.type eq bearer；
- boCreate.rewrite：`path` showWhen strategy ne no；`addFields` showWhen strategy in [add,boId]；
  `excludes` eq exclude；`includes` eq include；
- boQuery.main：`firstRow`/`rowCount` showWhen method eq listPage。

---

## 9. 与 cmp-defs 的双事实源过渡期安排

本件落地后短期内 label/icon/group/color 在 cmp-defs.ts 与 @DatabusCmp 两处各一份：

- **消费边界**：cmp-defs 继续供面板拖拽、节点渲染、EL 生成；@DatabusCmp 只供属性面板配置表单与组件台账，两者不交叉；
- **一致性纪律**：批 1 实施时注解值一律从 cmp-defs 逐项拷贝，评审以 diff 核对；后续每批同纪律；
- **退役动作（另立任务，不在本件）**：/options 合流稳定、所有批次迁完后，面板改拉 /options
  （算子/虚拟节点仍保留前端常量，它们是画布结构不是物料），cmp-defs 退化为只剩算子定义；
  退役前 color/icon 等以 cmp-defs 为准，发现不一致改注解对齐。

---

## 10. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 表单产出 JSON 字段顺序/形态与手填不一致导致 EL 或历史数据差异 | cmpData 是 Jackson 反序列化对象，键序无语义；试点实测含旧链路打开回归；存储通道不变 |
| Object 字段表单表达力不足（httpRequest.body 等） | JSON 高级模式永远可用且同级展示，不是藏起来的后门；不追求表单覆盖 100%（NiFi Custom UI 同范式） |
| 反射泛型擦除解析失败 | 解析不出一律兜底 JSON 控件，表单不崩；启动日志列出所有件的最终 widget 解析结果便于人工核对 |
| 注解漏标导致新件配置面板回退 | 回退＝现有 JsonCodeEditor 行为，不是故障；scanner 启动日志汇总「已注册 schema / 未注解件」两份清单 |
| /options 体积膨胀 | 23 件 × 平均 6 字段约几十 KB，模块级缓存一次拉取；第二步脚本原子上量后再评估分页/按需 |
| 双模式同步把坏 JSON 写进 ElNode | 同步纪律 §6.3：坏 JSON 不覆盖表单、写回通道沿用现状；保存画布时 JSON 模式做合法性拦截 |
| 与第二步脚本原子 schema 契约不合 | **已提前消除（2026-10-02 拍板，见 §5.5）**：脚本原子用 javax-pro Java 类脚本，打同一套注解、内嵌 Cfg 走同一反射器，param_schema 同构，消费层零改 |

---

## 11. 验收总标准

1. 后端：`mvnw compile` 通过；启动日志可见 **22 个**物料 schema 注册（20 个注解 Java 件 + 2 脚本补充件；switchRoute 不注册）；/options 返回合流结构、未注解件不影响接口；
2. 前端：oxlint / vue-tsc / IDE 诊断无新增问题；
3. setValue 试点 §7 实测清单已全部通过（2026-10-02 用户亲测收线）；推广批 19 件实测清单（会话交付，含连接下拉/行内 OBJECT 弹层/KEY_VALUE_MAP DATA 值/showWhen 显隐四个新交互专项 + 存量老链路回显回归）待用户实测；
4. 零 DDL（databus_component 表结构不动）、cmpData 存储格式不变；condition/forLoop/iteratorLoop 存量 data JSON 打开即回显新表单，switchRoute 与脚本件面板行为不变；
5. 组件管理页可看到 22 个注册物料的只读台账；
6. 测试由用户亲自执行（遵循项目约定，AI 不代跑测试）。
