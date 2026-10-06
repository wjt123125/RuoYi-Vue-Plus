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
    domain            varchar(16)     default null              comment '业务叶子业务域（bpm=BPM 平台集成件/common=通用加工；仅 group_name=business 普通叶子使用，条件槽件留空；空按 common 展示）【治理】',
    icon              varchar(100)    default null              comment '图标（svg 名或 Iconify 名如 ph:atom）【治理】',
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
    key idx_group_name (group_name)
) engine=innodb comment='数据总线组件元信息表（原子物料的家：契约缓存+治理真身+脚本工件）';

-- ----------------------------
-- 菜单：组件管理（2026-10-06 终局正本，menu_id 20009~014）
-- 挂父目录 1761400000000020000（数据总线，由 databus_chain.sql 播种）。
-- 旧 050~055（component/version 脚本历史种子，与本段同 path 同 perms）已废弃：
--   连授权一并清除，防止两套「组件管理」菜单并存。
-- 幂等：sys_menu 先按 id 删后插，sys_role_menu 中 20009~014 的角色授权不动
--   （删除的是菜单行、同 id 立即重建，授权行继续有效；两表无物理外键），可直接重复执行。
-- 台账页同时消费 /databus/component/options 与 /list，授权时组件管理与编辑器权限需一并授予。
-- ----------------------------
delete from sys_role_menu where menu_id in (1762000000000000050, 1762000000000000051, 1762000000000000052, 1762000000000000053, 1762000000000000054, 1762000000000000055);
delete from sys_menu where menu_id in (1762000000000000050, 1762000000000000051, 1762000000000000052, 1762000000000000053, 1762000000000000054, 1762000000000000055,
                                      1761400000000020009, 1761400000000020010, 1761400000000020011, 1761400000000020012, 1761400000000020013, 1761400000000020014);

insert into sys_menu values
  (1761400000000020009, '组件管理', 1761400000000020000, 2, 'component', 'databus/component/index', '', 'N', 'Y', 'C', '0', '0', 'databus:component:list', 'component', '', '', NULL, NULL, sysdate(), NULL, NULL, '组件元信息管理'),
  (1761400000000020010, '组件查询', 1761400000000020009, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1761400000000020011, '组件新增', 1761400000000020009, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1761400000000020012, '组件修改', 1761400000000020009, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1761400000000020013, '组件删除', 1761400000000020009, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1761400000000020014, '脚本编辑', 1761400000000020009, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:script:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '受信作者：保存即编译热更，等同服务端代码发布');
