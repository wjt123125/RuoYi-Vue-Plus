-- ----------------------------
-- 数据总线执行记录两表 databus_execution / databus_execution_node
-- 2026-09-29 按设计文档 §4.2/§4.3 定稿建表：
-- 审计（每步入参/出参/状态/耗时/分支）+ 重跑（入参原样留存）两个核心目的。
-- 两表只增不改：无 del_flag 逻辑删除，过期数据由保留期清理任务物理删除（backlog，本件不实现）。
-- 采集走「追踪牌 + LiteFlow 框架钩子」：
--   execute() 先查 databus_chain 按 log_level 决定档位（OFF 不挂牌、不落库），
--   BASIC 只写执行级总账，FULL 总账 + 节点级每步 IO；
--   总账独立事务必达，节点明细尽力批量插入、失败只 warn 不连累总账。
-- ----------------------------

-- ----------------------------
-- 执行级总账表
-- 链路查不到（ChainNotFound 类失败）时 chain_id 允许为空，chain_code 仍记录调用目标；
-- request_data 为执行时数据树根 JSON，重跑直接取它走正式 execute 通道（产生新记录）。
-- ----------------------------
drop table if exists databus_execution;
create table databus_execution (
    id                bigint(20)      not null                  comment '执行记录id（executionId）',
    chain_id          bigint(20)      default null              comment '链路id（链路查不到的失败记录为空）',
    chain_code        varchar(100)    not null                  comment '链路编码（冗余，列表查询/重跑定位用）',
    request_data      longtext        default null              comment '执行入参 JSON（数据树根，重跑据此还原）',
    response_data     longtext        default null              comment '最终输出 JSON（执行结束时数据树快照）',
    status            varchar(16)     not null                  comment '执行状态（RUNNING进行中/SUCCESS成功/FAILED失败）',
    error_msg         text            default null              comment '失败时错误信息',
    start_time        datetime        default null              comment '执行开始时间',
    end_time          datetime        default null              comment '执行结束时间',
    duration          bigint(20)      default null              comment '执行耗时（毫秒）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    primary key (id),
    key idx_exec_chain_id (chain_id),
    key idx_exec_chain_code (chain_code),
    key idx_exec_start_time (start_time)
) engine=innodb comment='数据总线链路执行记录（执行级总账）';

-- ----------------------------
-- 节点级明细表（仅 FULL 档写入）
-- node_instance_id 取 CmpStep.getNodeInstanceId()，区分 FOR/ITERATOR 循环多轮同 tag 的多行；
-- tag=组件实例 tag（数据空间名），node_type=LiteFlow 注册类型名（实例唯一性靠 tag）。
-- input_json/output_json 为数据树当场快照（slot.getInput/getOutput 普通组件恒空，不可用）；
-- branch_info 记分支/轮次（IF 真假、SWITCH 命中 case、循环第几轮）。
-- ----------------------------
drop table if exists databus_execution_node;
create table databus_execution_node (
    id                bigint(20)      not null                  comment '主键id',
    execution_id      bigint(20)      not null                  comment '执行记录id（databus_execution.id）',
    node_instance_id  varchar(64)     default null              comment '节点实例id（CmpStep.refNode.nodeInstanceId，需开 liteflow.enable-node-instance-id；区分同 nodeId 多次出现，循环多轮靠 branch_info 的 LOOP 轮次）',
    tag               varchar(100)    not null                  comment '节点 tag（组件实例标识/数据空间名）',
    node_type         varchar(64)     not null                  comment '组件注册类型名（httpRequest/condition/forLoop 等）',
    input_json        longtext        default null              comment '节点执行前数据树快照（可缺省）',
    output_json       longtext        default null              comment '节点执行后 $.<tag> 数据空间快照（布尔组件记判定结果）',
    status            varchar(16)     not null                  comment '节点状态（SUCCESS成功/FAILED失败）',
    error_msg         text            default null              comment '失败时错误信息',
    start_time        datetime        default null              comment '节点开始时间（CmpStep.startTime）',
    end_time          datetime        default null              comment '节点结束时间（CmpStep.endTime）',
    duration          bigint(20)      default null              comment '节点耗时（毫秒，CmpStep.timeSpent）',
    branch_info       varchar(500)    default null              comment '分支标记（IF 真假/SWITCH 命中 case/循环轮次）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    primary key (id),
    key idx_node_execution_id (execution_id)
) engine=innodb comment='数据总线执行节点记录（节点级明细，FULL 档采集）';

-- ----------------------------
-- 菜单与权限（执行记录页：列表/查询/手动执行与重跑）
-- 前端动态路由：component 填 databus/execution/index 自动映射 views/databus/execution/index.vue。
-- 复用父菜单"数据总线"目录 menu_id=1761400000000020000（连接管理 010~015、链路管理 020~026/030 同父）。
-- 链路卡片上的「执行」按钮与记录页/详情抽屉的「手动执行/重跑」共用 databus:execution:execute。
-- 注意：parent_id 必须指向真实存在的父菜单；非 admin 账号还需在【角色管理】勾选这 3 项（sys_role_menu）。
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- icon 必须用 plus-ui/src/assets/icons/svg 下真实存在的图标名（侧边栏 svg-icon 渲染），
-- 执行记录取 log.svg（与操作日志同义）；早期误写的 'tickets' 不存在会渲染空白。
-- 幂等：先按 menu_id 删旧（含 sys_role_menu 关联）再插，本片段可直接重复执行。
-- ----------------------------
delete from sys_role_menu where menu_id in (1762000000000000040, 1762000000000000041, 1762000000000000042);
delete from sys_menu where menu_id in (1762000000000000040, 1762000000000000041, 1762000000000000042);

insert into sys_menu values
  (1762000000000000040, '执行记录', 1761400000000020000, 3, 'execution', 'databus/execution/index', '', 'N', 'N', 'C', '0', '0', 'databus:execution:list', 'log', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线执行记录台账菜单'),
  (1762000000000000041, '记录查询', 1762000000000000040, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询执行记录详情（总账+节点明细）'),
  (1762000000000000042, '手动执行/重跑', 1762000000000000040, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:execute', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动执行已发布链路/以历史入参重跑，均产生新记录');
