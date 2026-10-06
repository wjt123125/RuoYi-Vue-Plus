-- ----------------------------
-- 数据总线脚本组件版本表 databus_component_version
-- 2026-10-06 javax-pro 脚本宿主（提前开工）配套 DDL。
--
-- 语义：databus_component.script_body 每保存一版（保存即编译、失败不落库），
--   本表追加一行（append-only 审计），主表 version 指向当前版本号；
--   一键回滚＝取目标版本源码重走保存管线，产生一条「内容等同旧版」的新版本行，
--   不覆盖、不删除历史行（remark 记录回滚来源）。
--
-- 与主表关系：逻辑外键 component_id → databus_component.id（不建物理 FK，
--   组件删除走逻辑删 del_flag，版本行随组件留存审计；无级联）。
--
-- 幂等：create table if not exists，本片段可直接重复执行（不动存量版本数据）。
-- ----------------------------
create table if not exists databus_component_version (
    id                bigint(20)      not null                  comment '主键id',
    component_id      bigint(20)      not null                  comment '组件主键（databus_component.id）',
    version_no        int(11)         not null                  comment '版本号（组件内自增，1 起）',
    script_lang       varchar(16)     default null              comment '脚本语言（java/groovy 等，一期 java）',
    script_body       longtext        not null                  comment '该版本脚本正文（完整 Java 类源码）',
    remark            varchar(500)    default null              comment '版本备注（如：回滚自版本 v3）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    primary key (id),
    unique key uk_component_version (component_id, version_no),
    key idx_component_id (component_id)
) engine=innodb comment='数据总线脚本组件版本历史（append-only 审计与一键回滚）';

-- ----------------------------
-- 菜单：脚本编辑按钮（2026-10-06 脚本宿主启用）
-- 挂组件管理目录 1762000000000000050 下，编号 055（051~054 已被查询/新增/修改/删除占用）。
-- 受信作者模型：脚本保存＝服务器任意代码执行能力，本权限只授予平台实施/超管；
--   治理列编辑仍走 databus:component:edit（可下放运营），两端点分离鉴权。
-- 幂等：先按 menu_id 删旧（含 sys_role_menu 关联）再插，可直接重复执行；
--   重跑刷新菜单定义，代价是角色需重新勾选该项授权。
-- ----------------------------
delete from sys_role_menu where menu_id = 1762000000000000055;
delete from sys_menu where menu_id = 1762000000000000055;

insert into sys_menu values
  (1762000000000000055, '脚本编辑', 1762000000000000050, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:script:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '编辑组件 Java 脚本源码（保存即编译热更，等同服务器代码发布权限，仅授平台实施/超管）');
