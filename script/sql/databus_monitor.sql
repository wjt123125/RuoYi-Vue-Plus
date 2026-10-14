-- ----------------------------
-- 数据总线监控体系（阶段5）指标聚合三表 + 菜单权限
-- 数据源：databus_execution（执行级总账）/ databus_execution_node（节点级明细，仅 FULL 档）
-- 聚合口径：按整点小时桶（stat_hour）预聚合落宽表，大盘/趋势/排行榜只读宽表，不回扫原始执行记录。
-- 档位容忍：log_level=OFF 的执行不落库、BASIC 不落节点明细，故指标天然只覆盖有记录的链路/节点。
-- 幂等重跑：聚合任务对目标小时先删后插（见 DatabusMetricServiceImpl.aggregate），重复执行结果一致。
-- 清理：三表随 databus_execution 保留期策略同步清理（聚合任务内调用 DatabusMetricServiceImpl.cleanExpired，同一 retention-days）。
-- ----------------------------

-- ----------------------------
-- 链路级小时聚合宽表：一行 = 一条链路一小时
-- ----------------------------
drop table if exists databus_metric_hourly;
create table databus_metric_hourly (
    id             bigint(20)   not null                  comment '主键id',
    chain_id       bigint(20)   default null              comment '链路id（链路查不到的失败执行无此值）',
    chain_code     varchar(100) not null                  comment '链路编码',
    stat_hour      datetime     not null                  comment '统计小时桶（整点，如 2026-10-12 14:00:00）',
    total_count    int(11)      not null default 0        comment '执行总次数',
    success_count  int(11)      not null default 0        comment '成功次数',
    failed_count   int(11)      not null default 0        comment '失败次数',
    success_rate   decimal(5,2) default null              comment '成功率（百分比 0-100；无样本为空）',
    avg_duration   bigint(20)   default null              comment '平均耗时（毫秒）',
    min_duration   bigint(20)   default null              comment '最小耗时（毫秒）',
    max_duration   bigint(20)   default null              comment '最大耗时（毫秒）',
    p95_duration   bigint(20)   default null              comment 'P95 耗时（毫秒，最近秩法；样本不足取最大值）',
    p99_duration   bigint(20)   default null              comment 'P99 耗时（毫秒，最近秩法；样本不足取最大值）',
    create_dept    bigint(20)   default null              comment '创建部门',
    create_by      bigint(20)   default null              comment '创建者',
    create_time    datetime     default null              comment '创建时间',
    update_by      bigint(20)   default null              comment '更新者',
    update_time    datetime     default null              comment '更新时间',
    primary key (id),
    unique key uk_metric_hourly_chain (chain_code, stat_hour),
    key idx_metric_hourly_hour (stat_hour)
) engine=innodb comment='数据总线链路指标小时聚合宽表';

-- ----------------------------
-- 节点级小时聚合表：一行 = 一条链路的一个组件实例一小时（慢组件 Top10 数据源）
-- ----------------------------
drop table if exists databus_metric_node_hourly;
create table databus_metric_node_hourly (
    id             bigint(20)   not null                  comment '主键id',
    chain_code     varchar(100) not null                  comment '链路编码',
    tag            varchar(100) not null                  comment '节点 tag（组件实例标识/数据空间名）',
    node_type      varchar(64)  not null                  comment '组件注册类型名（httpRequest/condition 等）',
    stat_hour      datetime     not null                  comment '统计小时桶（整点）',
    total_count    int(11)      not null default 0        comment '调用次数',
    success_count  int(11)      not null default 0        comment '成功次数',
    failed_count   int(11)      not null default 0        comment '失败次数',
    avg_duration   bigint(20)   default null              comment '平均耗时（毫秒）',
    max_duration   bigint(20)   default null              comment '最大耗时（毫秒）',
    create_dept    bigint(20)   default null              comment '创建部门',
    create_by      bigint(20)   default null              comment '创建者',
    create_time    datetime     default null              comment '创建时间',
    update_by      bigint(20)   default null              comment '更新者',
    update_time    datetime     default null              comment '更新时间',
    primary key (id),
    unique key uk_metric_node_hourly (chain_code, tag, node_type, stat_hour),
    key idx_metric_node_hourly_hour (stat_hour)
) engine=innodb comment='数据总线节点指标小时聚合表';

-- ----------------------------
-- 错误聚合表：一行 = 一条链路一类错误指纹一小时（高频错误 Top10 数据源）
-- error_fingerprint 为归一化后的错误摘要（去数字/去 id/取首行），同指纹合并计数；error_sample 留原始首段便于排查。
-- ----------------------------
drop table if exists databus_metric_error_hourly;
create table databus_metric_error_hourly (
    id                bigint(20)   not null               comment '主键id',
    chain_code        varchar(100) not null               comment '链路编码',
    stat_hour         datetime     not null               comment '统计小时桶（整点）',
    error_fingerprint varchar(255) not null               comment '错误指纹（归一化摘要，去重维度）',
    error_sample      varchar(500) default null           comment '错误样例（原始错误信息首段）',
    happen_count      int(11)      not null default 0     comment '发生次数',
    create_dept       bigint(20)   default null           comment '创建部门',
    create_by         bigint(20)   default null           comment '创建者',
    create_time       datetime     default null           comment '创建时间',
    update_by         bigint(20)   default null           comment '更新者',
    update_time       datetime     default null           comment '更新时间',
    primary key (id),
    unique key uk_metric_error_hourly (chain_code, error_fingerprint, stat_hour),
    key idx_metric_error_hourly_hour (stat_hour)
) engine=innodb comment='数据总线错误指标小时聚合表';

-- ----------------------------
-- 菜单与权限（监控大盘；告警管理菜单见 databus_alert.sql）
-- 复用父菜单"数据总线"目录 menu_id=1761400000000020000；本段占 070~074。
-- ID 段红线：1762 段 010~015 连接管理、020~030 链路管理、040~044 链路工作台、050~055 组件管理、
--   060~063 执行记录；070 起为监控体系（070~074 大盘，075+ 告警），两侧脚本不得回改。
-- icon 必须用 plus-ui/src/assets/icons/svg 下真实存在的文件名：dashboard/message 均存在。
-- 幂等：先按 menu_id 删旧（含 sys_role_menu 关联）再逐行插，本片段可重复执行。
-- ----------------------------
delete from sys_role_menu where menu_id in (1762000000000000070, 1762000000000000071, 1762000000000000072);
delete from sys_menu where menu_id in (1762000000000000070, 1762000000000000071, 1762000000000000072);

insert into sys_menu values
  (1762000000000000070, '监控大盘', 1761400000000020000, 4, 'monitor', 'databus/monitor/index', '', 'N', 'N', 'C', '0', '0', 'databus:monitor:list', 'dashboard', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线监控大盘（指标卡片/趋势/排行榜/错误流）'),
  (1762000000000000071, '指标查询', 1762000000000000070, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:monitor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询大盘卡片/趋势/排行榜/错误流数据'),
  (1762000000000000072, '指标聚合', 1762000000000000070, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:monitor:aggregate', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动触发指定小时指标聚合（定时任务每小时自动执行）');