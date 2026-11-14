-- ----------------------------
-- AI agent 三表 databus_agent_run / databus_agent_step / databus_agent_message
-- 2026-11-10 按设计文档 §16.1 建表（切片 1：说话 + 落账，快照表随切片 3 再加）：
--   run     一次 AI 干活（身份与权限快照、run 句柄、TTL、seq 号源）
--   step    每次工具调用一条，审计用
--   message 推给前端的文字记录，同时与 step 共用 run.next_seq 号源
-- 三张表只增不改（run 只更状态），无过期清理任务：
--   run 的 TTL 由守卫惰性判定（查行时比对 expire_time，过期置 EXPIRED），不做扫描。
-- run_id 用 UUID 而非自增 id：自增可枚举，AI/攻击者能猜别人的 run（红线 15），
--   id 只做内部主键，run_id 才是交给前端与 AI 的唯一句柄。
-- seq 号源在 run 行上：update databus_agent_run set next_seq = next_seq + 1 where run_id = ?
--   后读回，InnoDB 行锁保证原子，多实例安全；step 与 message 共用，才排得出全局先后。
-- 幂等：整份脚本可重复执行。开发阶段先删后建（会清空这四张表的数据），生产环境勿直接整份执行。
-- ----------------------------

drop table if exists databus_agent_conversation_meta;
drop table if exists databus_agent_message;
drop table if exists databus_agent_step;
drop table if exists databus_agent_snapshot;
drop table if exists databus_agent_run;

-- 一次 AI 干活。run_id 用 UUID，是交给 AI 的唯一句柄
create table if not exists databus_agent_run (
    id                   bigint(20)      not null                   comment '主键',
    run_id               varchar(64)     not null                   comment 'run 句柄，UUID，交给前端与 AI',
    user_id              bigint(20)      not null                   comment '发起人（身份快照来源，工具层据此 setCreateBy）',
    intent               varchar(1000)   default null               comment '用户原话',
    conversation_id      varchar(64)     default null               comment 'SnailAI 会话 id',
    round                int(11)         default 0                  comment '已用纠错轮数，上限取 databus.agent.max-rounds',
    status               varchar(20)     not null default 'RUNNING' comment 'RUNNING / FINISHED / FAILED / STOPPED / EXPIRED / SUPERSEDED',
    side_effect_approved char(1)        default '0'                comment '非幂等写的一次性人工授权（0未授权 1已授权），不跨 run 继承',
    permissions          text            default null               comment '发起时的权限点快照（JSON 数组）',
    before_payload       mediumtext      default null               comment 'AI 动手前的链路快照（最新一份，多轮见 databus_agent_snapshot）',
    report               mediumtext      default null               comment '最终报告六段（JSON 或 Markdown）',
    expire_time          datetime        default null               comment 'run TTL，超时置 EXPIRED（惰性判定，无扫描任务）',
    next_seq             int(11)         not null default 1         comment 'step 与 message 共用的 seq 号源，条件 UPDATE 原子自增',
    create_dept          bigint(20)      default null               comment '创建部门',
    create_by            bigint(20)      default null               comment '创建者',
    create_time          datetime        default null               comment '创建时间',
    update_by            bigint(20)      default null               comment '更新者',
    update_time          datetime        default null               comment '更新时间',
    del_flag             char(1)         default '0'                comment '删除标志（0存在 1删除）',
    remark               varchar(500)    default null               comment '备注',
    primary key (id),
    unique key uk_agent_run_run_id (run_id),
    key idx_agent_run_user_status (user_id, status),
    key idx_agent_run_user_conv_time (user_id, conversation_id, create_time)
) engine=innodb comment = 'AI agent 一次任务（身份与权限快照）';

-- 每次工具调用一条，只增不改（对照 databus_execution 的处置）
create table if not exists databus_agent_step (
    id                  bigint(20)      not null                   comment '主键',
    run_id              varchar(64)     not null                   comment '所属 run',
    seq                 int(11)         not null                   comment 'run 内单调递增序号',
    round               int(11)         default 0                  comment '第几轮',
    tool                varchar(64)     not null                   comment '工具名',
    args_digest         varchar(2000)   default null               comment '入参摘要（已脱敏，超长截断）',
    result_digest       varchar(4000)   default null               comment '返回摘要（已脱敏，超长截断）',
    success             char(1)         default '1'                comment '成功标志（0失败 1成功）',
    error_message       varchar(2000)   default null               comment '失败原文（javac / verifyMessage / NodeStep）',
    guard_reject_reason varchar(200)    default null               comment '守卫拒绝原因，非空表示本次被 §十五 拦下',
    cost_ms             bigint(20)      default null               comment '耗时毫秒',
    create_time         datetime        default null               comment '创建时间',
    primary key (id),
    unique key uk_agent_step_run_seq (run_id, seq),
    key idx_agent_step_tool (tool, create_time)
) engine=innodb comment = 'AI agent 工具调用审计';

-- 每轮动手前的链路快照，只增不改：供「回到这次对话前」按 run 整体回滚（设计文档 §9.2 / §16.1）
-- payload 存 DatabusChain 整行快照（cmpProperty / elExpression / canvasData / inputParams / logLevel）；
-- 用 mediumtext 而非 text：大链路的 cmpProperty + canvasData 可能超过 64KB，截断的快照等于回滚不了。
create table if not exists databus_agent_snapshot (
    id                  bigint(20)      not null                   comment '主键',
    run_id              varchar(64)     not null                   comment '所属 run',
    round               int(11)         not null default 0         comment '第几轮动手前（0 表示首轮）',
    operation           varchar(10)     not null default 'UPDATE'  comment 'CREATE 新建 / UPDATE 修改',
    payload             mediumtext      default null               comment '链路全量快照（JSON：cmpProperty/elExpression/canvasData/inputParams/logLevel）',
    create_time         datetime        default null               comment '创建时间',
    primary key (id),
    unique key uk_agent_snapshot_run_round (run_id, round)
) engine=innodb comment = 'AI agent 每轮动手前链路快照';

-- 推给前端的文字记录，同时是 seq 的号源（前端按 seq 保序）
create table if not exists databus_agent_message (
    id                  bigint(20)      not null                   comment '主键',
    run_id              varchar(64)     not null                   comment '所属 run',
    seq                 int(11)         not null                   comment 'run 内单调递增序号，前端按此保序',
    event_code          varchar(64)     not null                   comment 'databus:agent:canvas / draft / script-draft / run-end / auth',
    chain_id            bigint(20)      default null               comment 'canvas 事件带的链路 id',
    content             mediumtext      default null               comment '载荷（canvas 事件为 cmpProperty 全量快照）',
    create_time         datetime        default null               comment '创建时间',
    primary key (id),
    unique key uk_agent_message_run_seq (run_id, seq),
    key idx_agent_message_run (run_id, event_code)
) engine=innodb comment = 'AI agent 前端消息与 seq 号源';

-- 会话覆盖信息，只存用户改过的那几项（标题 / 置顶 / 工作区 / 软删）：
-- 会话本身仍由 databus_agent_run 聚合而来，本表不存标题副本以外的任何会话事实。
-- 「删除」只置 del_flag=1，run / step / message 历史全部保留，便于审计与找回。
create table if not exists databus_agent_conversation_meta (
    id              bigint(20)   not null auto_increment comment '主键',
    conversation_id varchar(64)  not null                comment '会话 id（与 databus_agent_run.conversation_id 对应）',
    user_id         bigint(20)   not null                comment '归属用户',
    workspace_id    bigint(20)   default null            comment '工作区 id；空=默认工作区',
    custom_title    varchar(255) default null            comment '用户自定义标题，空则回落首问摘要',
    pinned          char(1)      default '0'             comment '置顶（0否 1是）',
    del_flag        char(1)      default '0'             comment '软删标志（0存在 1删除）',
    create_time     datetime     default null            comment '创建时间',
    update_time     datetime     default null            comment '更新时间',
    primary key (id),
    unique key uk_conv_meta_conv (conversation_id),
    key idx_conv_meta_user (user_id, del_flag)
) engine=innodb comment = 'AI agent 会话覆盖信息（标题/置顶/软删/工作区）';

-- 会话级操作审批模式（阶段六）：一个会话一行，未落行按 MANUAL 处理。
-- 不并入 databus_agent_conversation_meta：审批模式是「本次会话怎么执行」的运行策略，
-- 与会话展示覆盖项（标题/置顶/软删）生命周期不同，且阶段禁令不允许往 meta 表加列。
drop table if exists databus_agent_approval_setting;
create table if not exists databus_agent_approval_setting (
    id              bigint(20)   not null auto_increment comment '主键',
    conversation_id varchar(64)  not null                comment '会话 id（与 databus_agent_run.conversation_id 对应）',
    user_id         bigint(20)   default null            comment '归属用户',
    mode            varchar(16)  not null default 'MANUAL' comment '审批模式 MANUAL/AUTO/FULL/CUSTOM',
    create_time     datetime     default null            comment '创建时间',
    update_time     datetime     default null            comment '更新时间',
    primary key (id),
    unique key uk_approval_conv (conversation_id)
) engine=innodb comment = 'AI 会话级操作审批模式';