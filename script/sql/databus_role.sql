-- ----------------------------
-- 数据总线角色与授权脚本 databus_role.sql
-- 目的：为验收/上线准备两个数据总线角色，并绑定到用户 test / test1。
--   · 数据总线操作员 databus_ops → 绑定 test ：数据总线目录下全部菜单，剔除「市场审核」。
--   · 数据总线治理员 databus_gov → 绑定 test1：数据总线目录下全部菜单（含「市场审核」）。
--   （市场审核有「禁自审」约束，故需两个账号才能跑通「上架→审核→上线」闭环。）
--
-- 授权范围按「数据总线」根菜单子树动态取，不逐个点 menu_id：
--   根 1761400000000020000 + 其直接子菜单 + 其孙菜单（当前菜单树仅三层）。
--   将来把「连接管理/监控大盘/告警管理」并入工作区、菜单增删改后，重跑本脚本即自动对齐，无需改 SQL。
--   操作员与治理员唯一差异：操作员排除「治理专属权限」，当前清单仅 databus:market:review 一条。
--
-- 幂等：角色、绑定、授权均先按 role_id 删除后重建，可重复执行（三表两两间无物理外键，先删后建不残留）。
-- 依赖：sys_menu 中数据总线菜单已建（databus_chain.sql / databus_workspace.sql 等）；
--       sys_user 中已存在 user_name = 'test' 与 'test1' 两个账号（不存在则该绑定静默跳过）。
-- 说明：data_scope 取 '1'（全部数据权限），避免验收时被部门数据范围误过滤。
-- ----------------------------

-- ----------------------------
-- 1、角色定义（先删后建）
-- ----------------------------
delete from sys_user_role where role_id in (1761300000000000010, 1761300000000000011);
delete from sys_role_menu where role_id in (1761300000000000010, 1761300000000000011);
delete from sys_role where role_id in (1761300000000000010, 1761300000000000011);

insert into sys_role values (1761300000000000010, '数据总线操作员', 'databus_ops', 10, '1', 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '数据总线平台操作角色：数据总线全目录权限，不含市场审核');
insert into sys_role values (1761300000000000011, '数据总线治理员', 'databus_gov', 11, '1', 1, 1, '0', '0', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '数据总线平台治理角色：数据总线全目录权限，含市场审核');

-- ----------------------------
-- 2、菜单授权（按「数据总线」子树动态取，先删后建）
--    层级：根 20000 → C 菜单(parent=20000) → F 按钮(parent=C)
-- ----------------------------
-- 2.1 治理员：整棵子树
insert into sys_role_menu (role_id, menu_id)
select 1761300000000000011, m.menu_id
from sys_menu m
where m.menu_id = 1761400000000020000
   or m.parent_id = 1761400000000020000
   or m.parent_id in (select menu_id from sys_menu where parent_id = 1761400000000020000);

-- 2.2 操作员：整棵子树，剔除治理专属权限（当前仅 databus:market:review）
insert into sys_role_menu (role_id, menu_id)
select 1761300000000000010, m.menu_id
from sys_menu m
where (m.menu_id = 1761400000000020000
   or m.parent_id = 1761400000000020000
   or m.parent_id in (select menu_id from sys_menu where parent_id = 1761400000000020000))
  and coalesce(m.perms, '') not in ('databus:market:review');

-- ----------------------------
-- 3、绑定用户（按 user_name 查 user_id；先删本角色绑定再插）
-- ----------------------------
insert into sys_user_role (user_id, role_id)
select u.user_id, 1761300000000000010 from sys_user u where u.user_name = 'test';

insert into sys_user_role (user_id, role_id)
select u.user_id, 1761300000000000011 from sys_user u where u.user_name = 'test1';