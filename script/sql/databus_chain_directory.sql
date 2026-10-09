-- ----------------------------
-- 数据总线链路目录表 databus_chain_directory
-- 2026-10-08 链路工作台化专项（decisions.md 第五章）：链路工作台资源树的组织层。
-- parent_id 自引用支持多级目录（0=根）；链表 directory_id 可空外键挂接（null=未归组，前端树虚拟节点兜底）。
-- 本表取代「主文件夹单选」旧方案：多级目录把组织权交给用户，业务域/部门降级为目录建设的推荐模板而非硬结构。
-- 将来多租户上线时补 tenant_id（数据隔离层，与组织层正交叠加，树结构与前端组件不变）。
-- ----------------------------
drop table if exists databus_chain_directory;
create table databus_chain_directory (
    id                bigint(20)      not null                  comment '主键id',
    parent_id         bigint(20)      default 0                  comment '父目录id（0=根目录，自引用多级树）',
    directory_name    varchar(100)    not null                  comment '目录名称（同级唯一）',
    sort              int(11)         default 0                  comment '排序（升序，值小在前，默认 0）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null               comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    key idx_directory_parent (parent_id)
) engine=innodb comment='数据总线链路目录表';

-- ----------------------------
-- 链表挂目录（可空外键：null/0 = 未归组链，工作台树兜底为「未归组」虚拟节点）
-- 注意：MySQL 8 的 add column 无 if not exists，重复执行会报列已存在（无害，可忽略）。
-- ----------------------------
alter table databus_chain add column directory_id bigint(20) default null comment '所属目录id（null=未归组，链路工作台树兜底为未归组虚拟节点；2026-10-08 增）';
alter table databus_chain add index idx_chain_directory (directory_id);

-- ----------------------------
-- 菜单与权限（链路工作台：三栏同构组件台——左 AI + 中多 tab 工作面板 + 右链资源树）
-- 前端走动态路由：菜单 component 填 databus/chain/workbench/index 即自动映射
-- views/databus/chain/workbench/index.vue（2026-10-09 起为链路唯一入口；旧编排器/链路管理
-- 两个页面菜单已在 databus_chain.sql 删除，工作台内嵌 ChainCanvasPane，不走 editor 路由）。
-- 权限 key 独立成 databus:chain:directory:* 前缀（目录是链路工作台的资源树层，非编排器语义）；
-- 工作台内 canvas tab / 链列表复用既有 databus:editor:* 权限（逻辑单源，不重复建点），
-- 这批 databus:editor:* F 行（含 030 list 载体、021~027、20002~20008）也已一并挂到本段 040 下，
-- 定义与幂等重建仍在 databus_chain.sql，两脚本配套执行。
-- 段 1762000000000000040~44：先删后插保证幂等（不动 sys_role_menu，同 id 重建授权继续有效）。
-- 非 admin 账号还需在【系统管理 → 角色管理】勾选本段 5 项菜单/按钮权限（sys_role_menu）。
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- ----------------------------
delete from sys_menu where menu_id between 1762000000000000040 and 1762000000000000044;

-- 逐行单条 insert（不复用一条多行 values 批量）：本机 mysql 客户端在「多行批量 + 中文」
-- 组合下稳定报 1264 Out of range（单条中文已验证稳定），拆行对标准 MySQL 无任何副作用。
insert into sys_menu values (1762000000000000040, '链路工作台', 1761400000000020000, 2, 'chain-workbench', 'databus/chain/workbench/index', '', 'N', 'N', 'C', '0', '0', 'databus:chain:directory:list', 'tree-table', '', '', NULL, NULL, sysdate(), NULL, NULL, '链路工作台（三栏：AI 助手 + 多 tab 工作面板 + 链资源树）');
insert into sys_menu values (1762000000000000041, '目录查询', 1762000000000000040, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:chain:directory:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000042, '目录新增', 1762000000000000040, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:chain:directory:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作台树右键新建目录');
insert into sys_menu values (1762000000000000043, '目录修改', 1762000000000000040, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:chain:directory:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '重命名/排序/拖拽换父（后端防环）/链路归属移动');
insert into sys_menu values (1762000000000000044, '目录删除', 1762000000000000040, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:chain:directory:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '子目录非空/挂链非空均拦截，不做级联删除');
