-- ----------------------------
-- 数据总线工作区脚本 databus_workspace.sql（2026-10-17 工作区融合，workbench-unification.md §3/§5）
-- 本脚本取代已删除的 databus_chain_directory.sql，承接两件事：
--   ① 统一目录表 databus_directory（原 databus_chain_directory 退役；链路 + 自建组件混装叶子）
--   ② 用户收藏表 databus_user_favorite（个人化组织维度，fork 引用记录同构基座）
-- 菜单与授权正本已收敛至 script/sql/databus_menu.sql（2026-10-23 tabs 收敛终态，全量重建型），
--   本脚本不再播任何 sys_menu / sys_role_menu 行，只管两张工作台表的 DDL 与目录属主回填。
-- 执行顺序：本脚本（DDL+回填）与 databus_menu.sql（菜单全量重建）相互独立，任意先后。
--
-- DDL 一律 drop + 全量重建（本库惯例，不写 alter）。旧目录表数据为开发期运行时数据，不迁移；
--   链表/组件表悬空的 directory_id 落「未归组」虚拟节点兜底。
-- ----------------------------

-- ----------------------------
-- 统一目录表 databus_directory（取代 databus_chain_directory，原列全保留 + owner_id）
-- parent_id 自引用支持多级目录（0=根）；owner_id 为 PaaS 化「每人一棵树」预留（null=团队共享树）。
-- 叶子混装链路（databus_chain.directory_id）与自建组件（databus_component.directory_id）。
-- ----------------------------
-- 旧目录表 databus_chain_directory 已被本表取代（代码零引用），顺带清掉避免残留。
drop table if exists databus_chain_directory;
drop table if exists databus_directory;
create table databus_directory (
    id                bigint(20)      not null                  comment '主键id',
    parent_id         bigint(20)      default 0                  comment '父目录id（0=根目录，自引用多级树）',
    directory_name    varchar(100)    not null                  comment '目录名称（同级唯一）',
    owner_id          bigint(20)      default null               comment '属主（null=团队共享树；PaaS 化后=用户id，每人一棵）',
    sort              int(11)         default 0                  comment '排序（升序，值小在前，默认 0）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    key idx_directory_parent (parent_id),
    key idx_directory_owner (owner_id)
) engine=innodb comment='统一目录（链路+组件混装叶子的组织树）';

-- ----------------------------
-- 用户收藏表 databus_user_favorite（极简三业务列，先例 sys_user_role；无审计字段/无逻辑删除）
-- 唯一键 uk_favorite 兜底幂等：同用户同类型同资源只一行。
-- ----------------------------
drop table if exists databus_user_favorite;
create table databus_user_favorite (
    id                bigint(20)      not null                  comment '主键',
    user_id           bigint(20)      not null                  comment '用户id',
    resource_type     varchar(16)     not null                  comment '资源类型（chain / component）',
    resource_id       bigint(20)      not null                  comment '资源id',
    create_time       datetime        default current_timestamp  comment '收藏时间',
    primary key (id),
    unique key uk_favorite (user_id, resource_type, resource_id)
) engine=innodb comment='用户收藏（owner→source 引用，fork 同构基座）';

-- ----------------------------
-- 菜单与权限：正本已收敛至 script/sql/databus_menu.sql
--   （2026-10-23 tabs 收敛终态，全量重建型：开头按三段 id 范围清旧、再全量 insert，
--   1 条 M 目录 20000 + 54 条 F 行直接挂靠，含 5 条 list 载体行；本脚本只管 DDL 与目录属主回填）
-- ----------------------------

-- ----------------------------
-- 目录属主回填（2026-10-22 实测纠偏，decisions §十二-2 启用 owner_id）
-- 资源树改按人隔离：存量「团队共享树」（owner_id null）行回填属主，否则创建者自己也看不见。
-- 全新环境刚建表为空行集，此段为无操作；幂等（owner_id is null 只补一次）。
-- ----------------------------
update databus_directory set owner_id = create_by where owner_id is null;
