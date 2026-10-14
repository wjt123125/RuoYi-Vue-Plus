-- ----------------------------
-- 数据总线告警引擎 v1（阶段5）规则表 + 记录表 + 菜单权限
-- 评估源：databus_execution（执行级总账）；评估时机：每分钟扫描启用规则（Spring @Scheduled，见 DatabusAlertEvaluateJob）。
-- 规则类型：CONSECUTIVE_FAILURE（窗口内最近 N 次执行全部失败）/ SUCCESS_RATE（窗口内成功率低于阈值）。
-- 记录状态机：一次告警生命周期一行——触发时插 FIRING（并发通知邮件），恢复时把该行翻成 RECOVERED 并回填 recover_time；
--   持续触发/持续正常期间不新增行（去重，避免每分钟重复通知）。
-- 通知渠道：v1 邮件（ruoyi-common-mail，mail.enabled=true 且配好 SMTP 才真正发出，否则 notify_status=FAILED 留痕）。
-- ----------------------------

-- ----------------------------
-- 告警规则表
-- ----------------------------
drop table if exists databus_alert_rule;
create table databus_alert_rule (
    id              bigint(20)   not null                  comment '主键id',
    rule_name       varchar(100) not null                  comment '规则名称',
    chain_code      varchar(100) not null                  comment '目标链路编码',
    rule_type       varchar(32)  not null                  comment '规则类型（CONSECUTIVE_FAILURE连续失败 / SUCCESS_RATE成功率低于阈值）',
    window_minutes  int(11)      not null default 5        comment '评估窗口（分钟）',
    threshold_count int(11)      default null              comment '连续失败次数阈值（CONSECUTIVE_FAILURE 用）',
    threshold_rate  decimal(5,2) default null              comment '成功率下限百分比（SUCCESS_RATE 用，0-100）',
    notify_emails   varchar(500) not null                  comment '通知邮箱（多个用逗号或分号分隔）',
    enabled         char(1)      default 'Y'               comment '是否启用（Y启用 N停用）',
    remark          varchar(500) default null              comment '备注',
    create_dept     bigint(20)   default null              comment '创建部门',
    create_by       bigint(20)   default null              comment '创建者',
    create_time     datetime     default null              comment '创建时间',
    update_by       bigint(20)   default null              comment '更新者',
    update_time     datetime     default null              comment '更新时间',
    primary key (id),
    key idx_alert_rule_chain (chain_code),
    key idx_alert_rule_enabled (enabled)
) engine=innodb comment='数据总线告警规则表';

-- ----------------------------
-- 告警记录表（行只增不改状态字段，恢复时翻转状态）
-- ----------------------------
drop table if exists databus_alert_record;
create table databus_alert_record (
    id            bigint(20)   not null                    comment '主键id',
    rule_id       bigint(20)   not null                    comment '规则id',
    rule_name     varchar(100) default null                comment '规则名称（触发时快照）',
    chain_code    varchar(100) default null                comment '目标链路编码（触发时快照）',
    alert_type    varchar(32)  not null                    comment '告警类型（触发时快照）',
    status        varchar(16)  not null                    comment '状态（FIRING触发中 / RECOVERED已恢复）',
    message       varchar(500) default null                comment '告警摘要',
    notify_emails varchar(500) default null                comment '通知对象（触发时快照）',
    notify_status varchar(16)  default null                comment '通知结果（SUCCESS成功 / FAILED失败 / SKIPPED未配置收件人）',
    trigger_time  datetime     not null                    comment '触发时间',
    recover_time  datetime     default null                comment '恢复时间（FIRING 时为空）',
    create_dept   bigint(20)   default null                comment '创建部门',
    create_by     bigint(20)   default null                comment '创建者',
    create_time   datetime     default null                comment '创建时间',
    update_by     bigint(20)   default null                comment '更新者',
    update_time   datetime     default null                comment '更新时间',
    primary key (id),
    key idx_alert_record_rule (rule_id, status),
    key idx_alert_record_trigger (trigger_time)
) engine=innodb comment='数据总线告警记录表';

-- ----------------------------
-- 菜单与权限（告警管理）
-- 复用父菜单"数据总线"目录 menu_id=1761400000000020000；本段占 075~080。
-- ID 段红线：1762 段 010~015 连接管理、020~030 链路管理、040~044 链路工作台、050~055 组件管理、
--   060~063 执行记录、070~074 监控大盘；075 起为告警管理（075~080），两侧脚本不得回改。
-- icon 必须用 plus-ui/src/assets/icons/svg 下真实存在的文件名：message 存在。
-- 幂等：先按 menu_id 删旧（含 sys_role_menu 关联）再逐行插，本片段可重复执行。
-- ----------------------------
delete from sys_role_menu where menu_id in (1762000000000000075, 1762000000000000076, 1762000000000000077,
                                            1762000000000000078, 1762000000000000079, 1762000000000000080);
delete from sys_menu where menu_id in (1762000000000000075, 1762000000000000076, 1762000000000000077,
                                       1762000000000000078, 1762000000000000079, 1762000000000000080);

insert into sys_menu values
  (1762000000000000075, '告警管理', 1761400000000020000, 5, 'alert', 'databus/alert/index', '', 'N', 'N', 'C', '0', '0', 'databus:alert:list', 'message', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线告警管理（规则配置/告警记录）'),
  (1762000000000000076, '告警查询', 1762000000000000075, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询告警规则与告警记录'),
  (1762000000000000077, '告警新增', 1762000000000000075, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '新增告警规则'),
  (1762000000000000078, '告警修改', 1762000000000000075, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '修改告警规则'),
  (1762000000000000079, '告警删除', 1762000000000000075, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '删除告警规则'),
  (1762000000000000080, '手动评估', 1762000000000000075, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:evaluate', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动触发一次告警规则评估（定时任务每分钟自动执行）');