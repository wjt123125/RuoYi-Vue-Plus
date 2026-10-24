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
    directory_id      bigint(20)      default null               comment '所属目录id（null=未归组，链路工作台树兜底为未归组虚拟节点；2026-10-08 增）',
    source_chain_id   bigint(20)     default null               comment '市场安装fork品溯源：源链id（NULL=原生链；链路安装全fork级联副本记源链，撞名复用判同源用，2026-10-23 增 decisions §十四-2）',
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
    unique key uk_chain_code (chain_code),
    key idx_chain_directory (directory_id)
) engine=innodb comment='数据总线链路定义表';

-- ----------------------------
-- 菜单与权限：正本已收敛至 script/sql/databus_menu.sql
--   （2026-10-23 tabs 收敛终态，全量重建型：开头按三段 id 范围清旧、再全量 insert，
--   1 条 M 目录 20000 + 54 条 F 行直接挂靠，含 5 条 list 载体行；本脚本只管 DDL 与字典数据）
-- ----------------------------

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
