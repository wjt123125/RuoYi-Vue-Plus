-- ----------------------------
-- 组件插入推荐：种子经验表 + 真实选择计数表
-- 2026-10-06 初版。
--
-- 设计（一句话：老师傅写的开业手册 + 真实经营攒下的账，后端揉成一份排序给前端）：
--   databus_cmp_recommend_seed  种子分：上线前按组件语义（cmp-defs 的 desc）人工编写，
--                               只覆盖高置信的前后置关系；anchor_type='ANY' 为通用基线。
--                               可随版本迭代覆盖（insert ignore，改分先按 id 删行）。
--   databus_cmp_usage          真账：前端每次在弹层选中组件上报一次，按三元组 upsert 计数。
--
-- 出分规则（DatabusRecommendServiceImpl）：
--   最终分 = min(100, 种子分(缺省40) + min(60, 15 * log2(1 + 真账次数)))
--   即种子给基线，真实使用最多再抬 60 分；新系统种子说了算，真账越多真账越说了算。
--
-- scene 取 PickerMode：prepend / append / replace / insertEdge；'any' 为全场景回退。
-- 前端 replace 场景只用本地同类规则、不拉后端（同类互转规则已足够准）。
-- ----------------------------

drop table if exists databus_cmp_recommend_seed;
create table databus_cmp_recommend_seed (
    id           bigint(20)   not null                  comment '主键id',
    anchor_type  varchar(64)  not null                  comment '前置组件 def.type；ANY=不看前置的通用基线',
    picked_type  varchar(64)  not null                  comment '被推荐组件 def.type',
    scene        varchar(32)  not null default 'any'    comment '插入场景（prepend/append/replace/insertEdge/any）',
    score        int(11)      not null default 50       comment '种子分 0-100，仅覆盖高置信关系',
    reason       varchar(200) default null              comment '打分理由（便于审阅，desc 依据）',
    create_time  datetime     default null              comment '创建时间',
    primary key (id),
    unique key uk_seed_triple (anchor_type, picked_type, scene)
) engine=innodb comment='组件推荐种子经验表（人工编写，可版本覆盖）';

drop table if exists databus_cmp_usage;
create table databus_cmp_usage (
    id           bigint(20)   not null                  comment '主键id',
    anchor_type  varchar(64)  not null                  comment '前置组件 def.type；无锚点记 ANY',
    picked_type  varchar(64)  not null                  comment '被选中组件 def.type',
    scene        varchar(32)  not null                  comment '插入场景（prepend/append/replace/insertEdge）',
    pick_count   int(11)      not null default 0        comment '累计被选中次数',
    create_time  datetime     default null              comment '首次选中时间',
    update_time  datetime     default null              comment '最近选中时间',
    primary key (id),
    unique key uk_usage_triple (anchor_type, picked_type, scene)
) engine=innodb comment='组件选择真账计数表（前端上报，upsert 累加）';

-- ----------------------------
-- 种子数据：全部 scene='any'。
-- 分数量纲与前端本地规则分对齐（前端推荐区门槛 75）：
--   85-92 强契约：desc 明写的输入输出依赖（如 processStart→boCreate.bindId）；
--   78-82 强惯例：典型编排主线的高频后继，进推荐区；
--   ~70   通用高频基线（anchor_type='ANY'）：贴着门槛，无专属关系时占推荐位；
--   ≤68   弱关联：只影响 tab 列表内排序，不进推荐区。
-- 前端融合语义：远程分覆盖本地规则分，无信号类型保留本地分（利于新组件冷启动曝光）。
-- id 用 1801 起固定序号，insert ignore 幂等；调分请先 delete 对应行再重跑。
-- ----------------------------
insert ignore into databus_cmp_recommend_seed
  (id, anchor_type, picked_type, scene, score, reason, create_time) values
  -- ── 通用基线：任意前置之后的高频组件（贴门槛，不喧宾夺主） ──
  (1801001, 'ANY', 'THEN',          'any', 70, '多数位置都可串行收束',      sysdate()),
  (1801002, 'ANY', 'httpRequest',   'any', 70, '通用协议调用，高频叶子',    sysdate()),
  (1801003, 'ANY', 'setValue',      'any', 70, '写数据空间，通用叶子',      sysdate()),
  (1801004, 'ANY', 'fieldMap',      'any', 70, '字段搬运，通用加工叶子',    sysdate()),
  (1801005, 'ANY', 'script',        'any', 70, '任意逻辑兜底，高频叶子',    sysdate()),
  (1801006, 'ANY', 'dataPatch',     'any', 68, '对象补丁，加工类叶子',      sysdate()),
  (1801007, 'ANY', 'WHEN',          'any', 65, '并行编排次于串行',          sysdate()),
  (1801008, 'ANY', 'response',      'any', 65, '链路收尾组件，偏后置',      sysdate()),
  (1801009, 'ANY', 'IF',            'any', 60, '需要条件分流时使用',        sysdate()),

  -- ── BPM 主线：会话 → 启流程 → 建 BO → 查 → 改/补 → 回写 → 响应/完任务 ──
  (1801101, 'sessionCreate',   'processStart',    'any', 88, '登录拿 sid 后启动流程', sysdate()),
  (1801102, 'processStart',    'boCreate',        'any', 92, '产出 processInstanceId 供 boCreate.bindId 引用（desc 明写）', sysdate()),
  (1801103, 'boCreate',        'boQuery',         'any', 78, '建 BO 后常需查回确认',   sysdate()),
  (1801104, 'boQuery',         'dataPatch',       'any', 90, '查出数据打补丁后回写（desc 典型用途）', sysdate()),
  (1801105, 'boQuery',         'boUpdate',        'any', 86, '查-改-回写事务主线（desc 明写）', sysdate()),
  (1801106, 'boQuery',         'fieldMap',        'any', 82, '查询结果常需字段搬运',   sysdate()),
  (1801107, 'dataPatch',       'boUpdate',        'any', 92, '补丁后交 boUpdate 回写（desc 明写）', sysdate()),
  (1801108, 'fieldMap',        'boUpdate',        'any', 78, '字段映射后整体回写 BO',  sysdate()),
  (1801109, 'boUpdate',        'response',        'any', 78, '回写成功后设置链路返回', sysdate()),
  (1801110, 'boDelete',        'response',        'any', 78, '删除后返回处理结果',     sysdate()),
  (1801111, 'taskComplete',    'processTerminate','any', 78, '完任务后终止流程实例',   sysdate()),
  (1801112, 'processTerminate','response',        'any', 78, '终止后返回结果收尾',     sysdate()),
  (1801113, 'sessionCreate',   'boCreate',        'any', 68, '建 BO 也依赖会话 sid',   sysdate()),

  -- ── 协议/数据加工链 ──
  (1801201, 'httpRequest', 'fieldMap',   'any', 78, '响应抽取字段后做搬运',     sysdate()),
  (1801202, 'httpRequest', 'setValue',   'any', 68, '响应值写入数据空间',       sysdate()),
  (1801203, 'rdsExecute',  'fieldMap',   'any', 78, 'SQL 结果存数据空间后搬运', sysdate()),
  (1801204, 'rdsExecute',  'dataPatch',  'any', 76, '查询结果加工后回写',       sysdate()),
  (1801205, 'idCardToUserId', 'boCreate', 'any', 68, '换成 userId 后建/关联 BO', sysdate()),
  (1801206, 'idCardToUserId', 'httpRequest', 'any', 65, 'userId 供后续接口调用',  sysdate()),

  -- ── 附件链：下载结果可直接接上传（desc 明写） ──
  (1801301, 'fileDownload', 'fileUpload', 'any', 80, '下载产物可直接接上传组件（desc 明写）', sysdate()),
  (1801302, 'fileUpload',   'response',   'any', 68, '上传完成后返回结果',       sysdate()),

  -- ── 异常处理主线：CATCH 体内识别异常 → SWITCH 分流（两条 desc 均明写） ──
  (1801401, 'CATCH',            'exceptionInspect', 'any', 88, 'CATCH 异常处理体内识别异常（desc 明写）', sysdate()),
  (1801402, 'exceptionInspect', 'SWITCH',           'any', 80, '识别后接 SWITCH 按类型分流（desc 明写）', sysdate()),

  -- ── 算子 → 其条件槽组件（desc 明写的配对关系） ──
  (1801501, 'IF',       'condition',    'any', 90, 'IF 条件位需要布尔条件组件', sysdate()),
  (1801502, 'WHILE',    'condition',    'any', 85, 'WHILE 条件位需要布尔条件组件', sysdate()),
  (1801503, 'SWITCH',   'switchRoute',  'any', 90, 'SWITCH 条件位需要路由组件（desc 明写）', sysdate()),
  (1801504, 'FOR',      'forLoop',      'any', 90, 'FOR 条件位需要计数循环组件（desc 明写）', sysdate()),
  (1801505, 'ITERATOR', 'iteratorLoop', 'any', 90, 'ITERATOR 条件位需要迭代循环组件（desc 明写）', sysdate());
