-- ----------------------------
-- 数据总线链路定义表 databus_chain
-- 阶段 1A 建表，2026-09-22 按设计文档 §4.2 沉淀完整 DDL：
-- 轻量单表模式（status 三态：0草稿/1已发布/2已下线），
-- 草稿与发布共用一份 el_expression + canvas_data，发布即固化当前编排为运行版本，
-- 改了就得重发才生效；version 每次发布递增，但不存历史快照（MVP 拍板不做版本历史）。
-- log_level 字段控制执行记录档位（OFF/BASIC/FULL），默认 BASIC，挂字典 databus_log_level。
-- 三层物料模型第三层（链路实例）的落库表，引用 databus_component（编排引用）。
-- ----------------------------
drop table if exists databus_chain;
create table databus_chain (
    id                bigint(20)      not null                  comment '主键id',
    chain_code        varchar(100)    not null                  comment '链路编码（全局唯一）',
    chain_name        varchar(100)    not null                  comment '链路名称（用户可读）',
    version           int(11)         default 1                  comment '版本号（每次发布递增，草稿阶段恒为 1）',
    status            char(1)         default '0'                comment '状态（0草稿 1已发布 2已下线）',
    el_expression     text            default null               comment 'LiteFlow EL 表达式（由后端从 CmpProperty 权威生成）',
    canvas_data       text            default null               comment '画布 JSON（VueFlow nodes/edges 序列化，编辑器还原用）',
    cmp_property      text            default null               comment '画布逻辑组件树 JSON（CmpProperty 序列化，EL 权威源的输入；发布时据此提取脚本节点推 lf_script）',
    log_level         varchar(16)     default 'BASIC'            comment '执行记录档位（OFF/BASIC/FULL，默认 BASIC；OFF 不落库，BASIC 仅执行级，FULL 含节点级每步 IO）',
    input_params      text            default null               comment '链路入参登记表 JSON（ChainInputParam 列表：路径/类型/默认值/必填，2026-09-27 增）',
    is_template       char(1)         default '0'                comment '是否精选模板（0否 1是，2026-09-30 增；模板恒为草稿，不可发布）',
    template_desc     varchar(500)    default null               comment '模板说明（适用场景/前置条件，模板库卡片展示，标记模板时必填）',
    template_sort     int(11)         default 0                  comment '模板排序（升序，值小在前，默认 0；同值按 update_time desc）',
    create_dept       bigint(20)      default null               comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_chain_code (chain_code)
) engine=innodb comment='数据总线链路定义表';

-- ----------------------------
-- 菜单与权限
-- 2026-10-09 链路工作台化，两个旧页面入口下线：
--   /databus/editor（编排器直入，20001）与 /databus/chain（链路管理列表页，020）。
--   侧边栏只留「链路工作台」040（databus/chain/workbench/index，由 databus_chain_directory.sql 播种）；
--   工作台直接内嵌 ChainCanvasPane 组件开画布，不再依赖 /databus/editor 隐藏路由。
-- 但后端鉴权点不变：DatabusChain/Component/Connection/ScriptEngine/ElGenerate 各 Controller
--   仍统一校验 databus:editor:* 权限（工作台画布、链树、组件选择、连接选择都在调）。
-- 故本段只删 3 个 C 类页面菜单，F 类权限点一个不删，全部改挂工作台 040：
--   20001、020 直接删除并清理 sys_role_menu 残留（二者原本无角色授权）；
--   030 由隐藏 C 菜单同 id 转为 F 行，做 databus:editor:list 的唯一授权载体
--   （先删后插不动 sys_role_menu，原授权角色的 list 权限无感延续）；
--   20002~20008、021~027 改 parent_id=040；与 021~024 的同 perms 重复是历史既有设计，
--   两套 id 各有角色授权，均保留。
-- 注意：parent 040 由 databus_chain_directory.sql 创建，两脚本需配套执行；
--   单独先跑本脚本时 F 行临时父节点不存在，跑完目录脚本即归位。
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- ----------------------------
-- 顶层父菜单：数据总线目录（原 databus-meta 旧 databus_menu.sql 唯一有效段，2026-10-06 迁入正本）
-- 本文件与 connection/execution/component 各脚本的菜单全部挂在此节点下；
-- 只跑子脚本不建此节点，菜单会变孤儿（菜单管理可见、侧边栏不显示）。
-- ----------------------------
insert ignore into sys_menu values
  (1761400000000020000, '数据总线', 0, 7, 'databus', null, '', 'N', 'Y', 'M', '0', '0', '', 'tree', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线目录');

-- ----------------------------
-- 旧页面菜单清理（幂等）：编排器 20001、链路管理 020 连同 sys_role_menu 授权关系一并删除。
-- ----------------------------
delete from sys_role_menu where menu_id in (1761400000000020001, 1762000000000000020);
delete from sys_menu     where menu_id in (1761400000000020001, 1762000000000000020);

-- ----------------------------
-- databus:editor:* 权限点（工作台时代全部挂「链路工作台」040 下）
-- 幂等：先删后插 sys_menu 行、不动 sys_role_menu（同 id 立即重建，授权继续有效，两表无外键）。
-- order_num 10 起排：1~4 留给工作台自带的目录权限 041~044。
-- 030＝list 载体；021~027 链 CRUD/发布/下线/模板；20002~20008 编排器段（含 export/import/run）。
-- ----------------------------
delete from sys_menu where menu_id in
  (1762000000000000030,
   1761400000000020002, 1761400000000020003, 1761400000000020004, 1761400000000020005,
   1761400000000020006, 1761400000000020007, 1761400000000020008,
   1762000000000000021, 1762000000000000022, 1762000000000000023, 1762000000000000024,
   1762000000000000025, 1762000000000000026, 1762000000000000027);

-- 逐行单条 insert（与 databus_chain_directory.sql 同约定）：本机 mysql 客户端在
-- 「多行批量 values + 中文」组合下稳定报 1264 Out of range，拆行对标准 MySQL 无副作用。
insert into sys_menu values (1762000000000000030, '链路列表查看', 1762000000000000040, 10, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'databus:editor:list 唯一授权载体：链列表/统计、组件与脚本引擎列表、连接选择等 GET 端点共用；原隐藏编辑器菜单 030 转 F，角色授权关系保留');
insert into sys_menu values (1762000000000000021, '链路查询', 1762000000000000040, 11, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000022, '链路新增', 1762000000000000040, 12, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000023, '链路修改', 1762000000000000040, 13, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000024, '链路删除', 1762000000000000040, 14, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000025, '链路发布', 1762000000000000040, 15, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:publish', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '发布链路：status 0→1/2→1 + version+1');
insert into sys_menu values (1762000000000000026, '链路下线', 1762000000000000040, 16, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:offline', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '下线链路：status 1→2');
insert into sys_menu values (1762000000000000027, '链路模板标记', 1762000000000000040, 17, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:template', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '链路设为/取消精选模板（运营动作）；标记需填模板说明，已发布链路须先下线');
insert into sys_menu values (1761400000000020002, '编排器查询', 1762000000000000040, 21, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020003, '编排器新增', 1762000000000000040, 22, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020004, '编排器修改', 1762000000000000040, 23, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020005, '编排器删除', 1762000000000000040, 24, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020006, '编排器导出', 1762000000000000040, 25, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:export', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020007, '编排器导入', 1762000000000000040, 26, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:import', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020008, '编排器试运行', 1762000000000000040, 27, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:run', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '画布预览试运行（1A 仅 EL 生成与语法校验）');

-- ----------------------------
-- 字典 databus_log_level（链路执行记录档位）
-- 用于链路管理页 log_level 字段的下拉选项渲染。
-- 默认 BASIC：能审计能重跑（入参/出参/状态/耗时/错误），又不背节点明细存储成本。
-- 三档语义见设计文档 §5.4：
--   OFF   关闭：完全不落库，极致高吞吐场景
--   BASIC 基础：仅执行级信息（入参/出参/状态/耗时/错误）
--   FULL  完整：执行级 + 节点级每步 IO（关键链路 / 调试期）
-- sys_dict_type 9 列：dict_id, dict_name, dict_type, create_dept, create_by, create_time, update_by, update_time, remark
-- sys_dict_data 14 列：dict_code, dict_sort, dict_label, dict_value, dict_type, css_class, list_class, is_default,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- ----------------------------
insert ignore into sys_dict_type values
  (1761500000000000013, '数据总线执行记录档位', 'databus_log_level', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '数据总线链路执行记录档位');

insert ignore into sys_dict_data values
  (1761600000000000040, 1, '关闭',   'OFF',   'databus_log_level', '', 'info',    'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '完全不落库，极致高吞吐场景'),
  (1761600000000000041, 2, '基础',   'BASIC', 'databus_log_level', '', 'primary', 'Y', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '执行级信息（入参/出参/状态/耗时/错误），默认值'),
  (1761600000000000042, 3, '完整',   'FULL',  'databus_log_level', '', 'success', 'N', 1761000000000000103, 1761100000000000001, sysdate(), null, null, '执行级 + 节点级每步 IO，关键链路/调试期');
