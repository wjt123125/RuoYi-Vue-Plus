-- ----------------------------
-- 数据总线组件元信息表 databus_component
-- 2026-10-06 按终局「原子的家」重建（表无正式数据，drop/recreate 零迁移）。
--
-- 列分三种语义（2026-10-06 属性归属立法，正本见 .trae/work-state.md 组件管理页段）：
--   ① 契约缓存：node_type/editor/param_schema/data_example/input_schema/output_schema
--      第一步由 form 字段构造器直填；第二步 javax-pro 脚本原子保存管线编译反射物化回填，
--      form 转只读（schema 唯一事实源是脚本工件内的 @DatabusCmp/@DatabusProp，列只是缓存）。
--   ② 治理真身：component_name/short_name/icon/color/group_name/sort/category/tags/
--      status/deprecated/deprecate_note/doc_url/remark —— 终身独立列，form 富控件随改随存，
--      不产脚本版本、不走人审；未来未迁移 jar 件也可插同码覆盖行做停用/废弃/打标。
--   ③ 工件：script_lang/script_body/version —— 第二步脚本宿主启用，第一步置灰预留。
--
-- component_code 唯一键：自定义物料编码，链路 cmp_property 节点 "id" 引用此值；
--   与内置注解件同码时 /options 内置优先、DB 行丢弃（台账露出「编码冲突」）。
-- status（0启用 1停用）与 databus_chain 的 status（0草稿 1已发布 2已下线）语义不同；
--   deprecated（0正常 1废弃）与 status 停用不同：停用＝不进物料面板，
--   废弃＝老链路仍可见、面板置灰提示「别在新链路用」。
-- ----------------------------
drop table if exists databus_component;
create table databus_component (
    id                bigint(20)      not null                  comment '主键id',
    component_code    varchar(64)     not null                  comment '组件编码（链路 cmp_property 节点 "id" 引用，唯一）【契约·身份】',
    component_name    varchar(100)    not null                  comment '组件名称【治理】',
    short_name        varchar(50)     default null              comment '物料网格短名（缺省用组件名称）【治理】',
    category          varchar(32)     not null                  comment '台账五分类（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM）【治理】',
    group_name        varchar(32)     default null              comment '物料面板七组（flow/sequence/branch/loop/other/subflow/business）【治理】',
    domain            varchar(16)     default null              comment '业务叶子业务域（取值见 databus_component_domain 字典行；空＝未指派：普通叶子由前端按 databus_component_domain.is_default 行归入兜底域；node_type ≠ NODE 的件由后端派生为 slot，不走兜底）【治理】',
    directory_id      bigint(20)      default null              comment '归属目录id（null=未归组；databus_directory 统一资源树，2026-10-17 增）【治理】',
    icon              varchar(100)    default null              comment '图标（Iconify Phosphor 集图标名 ph:*）【治理】',
    color             varchar(16)     default null              comment '面板色值（如 #409eff）【治理】',
    sort              int(11)         default 100               comment '面板排序（升序）【治理】',
    description       varchar(500)    default null              comment '一句话描述【治理】',
    tags              varchar(255)    default null              comment '标签 JSON 数组（如 ["HTTP","MES"]，台账编目/AI 目录检索）【治理】',
    node_type         varchar(16)     default null              comment 'LiteFlow 节点类型（NODE/BOOLEAN/FOR/ITERATOR/SWITCH）【契约缓存】',
    editor            varchar(16)     default null              comment '配置形态（form/script）【契约缓存】',
    param_schema      text            default null              comment '参数 schema 体（仅 {"fields":[...]}，配置面板渲染契约）【契约缓存】',
    data_example      text            default null              comment '配置 JSON 示例（JSON 高级模式占位提示）【契约缓存】',
    input_schema      text            default null              comment '输入 Schema（连线校验预留）【契约缓存】',
    output_schema     text            default null              comment '输出 Schema（连线校验预留）【契约缓存】',
    script_lang       varchar(16)     default null              comment '脚本语言（java/groovy 等，第二步脚本宿主启用）【工件】',
    script_body       longtext        default null              comment '脚本正文（第二步脚本原子，javax-pro 完整 Java 类源码）【工件】',
    version           int(11)         default 1                 comment '脚本版本（热更自增；版本历史表第二步另设计）【工件】',
    status            char(1)         default '0'               comment '启停状态（0启用 1停用；停用不进物料面板）【治理】',
    deprecated        char(1)         default '0'               comment '废弃标记（0正常 1废弃；废弃件老链路可见、面板置灰）【治理】',
    deprecate_note    varchar(200)    default null              comment '废弃提示文案（引导替代组件）【治理】',
    doc_url           varchar(255)    default null              comment '文档链接（wiki 锚点或外部 URL）【治理】',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '内部备注【治理】',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_component_code (component_code),
    key idx_category (category),
    key idx_group_name (group_name),
    key idx_component_directory (directory_id)
) engine=innodb comment='数据总线组件元信息表（原子物料的家：契约缓存+治理真身+脚本工件）';

-- ----------------------------
-- 菜单与权限：正本已收敛至 script/sql/databus_menu.sql
--   （2026-10-23 tabs 收敛终态，全量重建型：开头按三段 id 范围清旧、再全量 insert，
--   1 条 M 目录 20000 + 54 条 F 行直接挂靠，含 5 条 list 载体行；本脚本只管 DDL）
-- ----------------------------
