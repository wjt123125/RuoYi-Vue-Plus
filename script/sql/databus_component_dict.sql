-- ----------------------------
-- 物料分类元数据字典 databus_component_dict.sql
-- 2026-10-07 元数据后端化（正本见 docs/wiki/databus-component-taxonomy-metadata.md §2）。
--
-- 内容：两张字典表（面板分组 group / 业务域 domain）DDL + seed，同族同批故同文件。
-- 目的：把前端硬编码的分组与业务域「中文名 / 色值 / 顺序 / 兜底域」落库为治理数据，
--   由 GET /databus/component/groups 与 /databus/component/domains 两个只读端点下发，
--   改中文名/调色/加域不再需要前端发版。
--
-- 红线①（group）：group_key 必须与前端 STRUCTURE_DEFS 静态件的 group 字面量**逐字一致**
--   （flow/sequence/branch/loop/other/subflow/business）。字典 key 若与之不符，
--   编辑器面板与台账树的 filter/map 会**静默丢件**（不报错，面板就是少了节点）。
--   group 的正本在 jar 注解 @DatabusCmp.group()，本表只是「合法值白名单 + 展示元数据」，
--   删字典行不会让物料消失，只会让它落不进任何组。
-- 红线②（domain）：domain 是纯 DB 治理列——@DatabusCmp 注解与 CmpSchema 均无 domain 字段，
--   本表是唯一正本。is_default='Y' 全表至多一行（本轮无写端点，靠本 seed 保证）；
--   domain 为空的普通叶子件由前端归入该兜底域。
-- 红线③（slot 派生）：domain_key='slot' 行只提供展示元数据，成员判定由后端在 /options
--   合流时按 node_type ≠ NODE 派生（forLoop/iteratorLoop/switchRoute/booleanScript 四件），
--   **不需要人工指派**，故不给这四件插 databus_component 治理行。
--
-- 幂等：DDL drop/create；seed 走 insert ignore（照 databus_component_seed.sql 体例）。
-- ID 段：1762000000001200001 起（group 七行 ...200001~200007，domain 三行 ...200101~200103）。
-- 零新菜单：两端点沿用 databus:editor:list 权限，本文件不插任何 sys_menu 行，
--   非 admin 账号无需重新授权。
-- ----------------------------
set names utf8mb4;

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

-- ----------------------------
-- seed：分组七行 + 业务域三行
-- label/color/sort 逐字抄自前端现有 PALETTE_GROUPS 与 BUSINESS_SECTION_META，
-- 保证字典化迁移后视觉零变化。
-- ----------------------------
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
