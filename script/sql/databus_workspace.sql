-- ----------------------------
-- 数据总线工作区脚本 databus_workspace.sql（2026-10-17 工作区融合，workbench-unification.md §3/§5）
-- 本脚本取代已删除的 databus_chain_directory.sql，承接三件事：
--   ① 统一目录表 databus_directory（原 databus_chain_directory 退役；链路 + 自建组件混装叶子）
--   ② 用户收藏表 databus_user_favorite（个人化组织维度，fork 引用记录同构基座）
--   ③ 菜单收敛：组件管理 C 行 20009 同 id 重插为「工作区」；目录四 F 行改 parent/perms 重插；
--      新增收藏三 F 行；删除链路工作台 C 行 040（含 sys_role_menu）。
-- 执行顺序：databus_chain.sql → databus_component.sql → 本脚本。
--   （chain/component 脚本内 F 行的 parent 指向 20009，本脚本建 20009 后归位。）
--
-- DDL 一律 drop + 全量重建（本库惯例，不写 alter）。旧目录表数据为开发期运行时数据，不迁移；
--   链表/组件表悬空的 directory_id 落「未归组」虚拟节点兜底。
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark
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
-- 菜单收敛
-- 先删后插幂等：20009（工作区 C 行）、041~44（目录 F 行）、20015~017（收藏 F 行）均按 id 删除后重插，
--   不动 sys_role_menu（同 id 立即重建，授权继续有效；两表无物理外键）。
-- 20009 同 id 重插：原「组件管理」C 行 → 「工作区」，授权关系自动保留。
-- 041~44：改 parent→20009、perms 前缀 databus:chain:directory→databus:directory。
-- 20015~017：新增收藏三 F 行（parent=20009，order 接组件五 F 行之后）。
-- 040（链路工作台 C 行）：删除并连 sys_role_menu，ID 段红线延续不再分配。
-- 单条 insert（不复用多行 values 批量）：本机 mysql 客户端「多行批量 + 中文」稳定报 1264。
-- ----------------------------
delete from sys_menu where menu_id in
  (1761400000000020009,
   1762000000000020041, 1762000000000020042, 1762000000000020043, 1762000000000020044,
   1761400000000020015, 1761400000000020016, 1761400000000020017);

insert into sys_menu values (1761400000000020009, '工作区', 1761400000000020000, 2, 'workspace', 'databus/workspace/index', '', 'N', 'N', 'C', '0', '0', 'databus:directory:list', 'tree-table', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区（单壳：AI 助手 + 多 tab 工作面板 + 统一资源树）');

-- 目录四 F 行（原 041~44：改 parent→20009、perms→databus:directory:*；id 不变保留授权）
insert into sys_menu values (1762000000000020041, '目录查询', 1761400000000020009, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000020042, '目录新增', 1761400000000020009, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区统一资源树右键新建目录');
insert into sys_menu values (1762000000000020043, '目录修改', 1761400000000020009, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '重命名/排序/拖拽换父（后端防环）/链路与组件归属移动');
insert into sys_menu values (1762000000000020044, '目录删除', 1761400000000020009, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '子目录非空/挂链路/挂组件均拦截，不做级联删除');

-- 收藏三 F 行（新增 20015~017，parent=20009，order 顺延组件五 F 行 1~5 之后）
insert into sys_menu values (1761400000000020015, '收藏查询', 1761400000000020009, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区统一资源树「我的收藏」列表');
insert into sys_menu values (1761400000000020016, '收藏新增', 1761400000000020009, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '叶子一键收藏');
insert into sys_menu values (1761400000000020017, '收藏取消', 1761400000000020009, 8, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '叶子/收藏条目取消收藏');

-- ----------------------------
-- 授权迁移补丁（上线安全关键，必须在 delete 040 的 sys_role_menu 行之前执行）
-- 原链路工作台(040)授权角色补授工作区(20009)，幂等可重放；否则仅授权过 040 的角色失明。
-- ----------------------------
insert into sys_role_menu (role_id, menu_id)
select distinct rm.role_id, 1761400000000020009 from sys_role_menu rm
where rm.menu_id = 1762000000000000040
  and not exists (select 1 from sys_role_menu x where x.role_id = rm.role_id and x.menu_id = 1761400000000020009);

-- ----------------------------
-- 删除链路工作台 C 行 040（先清 sys_role_menu 再删 sys_menu）
-- ----------------------------
delete from sys_role_menu where menu_id = 1762000000000000040;
delete from sys_menu     where menu_id = 1762000000000000040;
