-- ----------------------------
-- 物料市场脚本 databus_market.sql（2026-10-18 物料市场，databus-material-market.md §十/§十一/§十三）
-- 承载工作台内「市场」tab 的唯一后端三表（市场菜单 F 行正本见 script/sql/databus_menu.sql）：
--   ① databus_market_item      集市货架（一行＝一个已上架的组件/链路 + 展示信息 + 状态机 + 三计数缓存）
--   ② databus_market_category  分类目录（多级平表，parent_id 自引用，前端组树）
--   ③ databus_install  owner→source 引用记录（安装＝fork 血缘；同构承载收藏星标）
-- 执行顺序：本脚本（DDL+种子）与 databus_menu.sql（菜单全量重建）相互独立，任意先后。
--
-- DDL 一律 drop + 全量重建（本库惯例，不写 alter）。市场三表为新建表，无历史数据迁移。
--
-- 【对设计正本 §十 的落地细化，已在此声明】
--   ① databus_market_item 增 remark varchar(500)：承接审核驳回理由（正本 §七 驳回需带理由，
--      而本表不继承 BaseEntity、无 remark 字段来源，故表列自持）。
--   ② databus_market_item 增唯一键 uk_market_source(owner_id, resource_type, source_id)：
--      保证 publish「同源重复提交则更新回待审」的 upsert 用 selectOne 安全（同源至多一行）。
--   ③ 三表均无审计字段（create_by/update_by/create_dept）与逻辑删除列，与 databus_user_favorite 同构：
--      仅 create_time/update_time；删原件即物理删（作者主权，§八）。
--   ④ databus_market_item 增 review_by bigint / review_time datetime：承载审核人与审核时间（正本 §七 7.1
--      审核可追溯 + 禁自审；来源资源归属校验在服务层做，不落表）。
-- ----------------------------

-- ----------------------------
-- 市场物料表 databus_market_item（集市货架）
-- code_display = {ownerCode}/{sourceCode}（人看展示码，非运行时标识；运行用 u{ownerId}__ 命名空间）。
-- tags 为 JSON 数组文本（应用侧 JacksonTypeHandler 互转）。
-- 三计数（install_count / ref_count / fork_publish_count）为缓存列，异步刷新，不进实时路径（§九）。
-- ----------------------------
drop table if exists databus_market_item;
create table databus_market_item (
    id                  bigint(20)   not null                  comment '主键id',
    owner_id            bigint(20)   not null                  comment '上架者id（命名空间键）',
    owner_code          varchar(64)  not null                  comment '上架者展示码（登录名，code_display 前缀）',
    resource_type       varchar(16)  not null                  comment '物料类型（COMPONENT / CHAIN）',
    source_id           bigint(20)   not null                  comment '源资源id（链路主键 / 组件DB行id）',
    code_display        varchar(164) not null                  comment '展示码 {ownerCode}/{sourceCode}（人看）',
    name                varchar(100) not null                  comment '名称（上架时取自源资源）',
    summary             varchar(500) default null              comment '简介（上架者填写）',
    icon                varchar(64)  default null              comment '图标（上架时取自源资源）',
    color               varchar(32)  default null              comment '色值（上架时取自源资源）',
    category_id         bigint(20)   default null              comment '分类id（databus_market_category）',
    tags                varchar(500) default null              comment '标签列表（JSON 数组文本）',
    status              varchar(16)  not null                  comment '上架状态（PENDING / REJECTED / ONLINE / OFFLINE）',
    install_count       int(11)      default 0                 comment '安装数（缓存：指向本物料的 fork 记录条数）',
    ref_count           int(11)      default 0                 comment '被引用数（缓存：下游链路 nodeTree 引用源编码次数）',
    fork_publish_count  int(11)      default 0                 comment 'fork改后发布数（缓存：已装副本改后发布的条数）',
    create_time         datetime     default null              comment '创建时间',
    update_time         datetime     default null              comment '更新时间',
    remark              varchar(500) default null              comment '备注（审核驳回理由等）',
    modify_note         varchar(500) default null              comment '修改说明（P1 驳回-重提闭环：作者被驳回后重提时填写，审核者复核用）',
    review_by           bigint(20)   default null              comment '审核人id（治理者；通过/驳回均记）',
    review_time         datetime     default null              comment '审核时间',
    primary key (id),
    unique key uk_market_source (owner_id, resource_type, source_id),
    key idx_market_status_type (status, resource_type),
    key idx_market_owner (owner_id),
    key idx_market_category (category_id)
) engine=innodb comment='物料市场货架（用户共治集市：展示信息 + 状态机 + 计数缓存）';

-- ----------------------------
-- 市场分类表 databus_market_category（多级平表，parent_id 自引用，0=根；前端组树）
-- ----------------------------
drop table if exists databus_market_category;
create table databus_market_category (
    id          bigint(20)   not null                  comment '主键id',
    parent_id   bigint(20)   default 0                 comment '父分类id（0=根，自引用多级）',
    name        varchar(100) not null                  comment '分类名称',
    sort        int(11)      default 0                 comment '排序（升序，值小在前，默认 0）',
    primary key (id),
    key idx_market_category_parent (parent_id)
) engine=innodb comment='物料市场分类（多级平表，前端组树）';

-- ----------------------------
-- 安装记录表 databus_install（owner→source 引用记录，与收藏同构）
-- 一行＝某用户安装某市场物料一次。market_item_id 允许 null 以承载后期 FAVORITE（星标）语义。
-- content_hash 记落快照时的逻辑源码指纹：未改快照与原件同 hash → 复用源 bean（0 内存，§六）。
-- 唯一键 uk_install 兜底幂等：同用户同物料至多一条安装记录（install 前置校验的护栏）。
-- directory_id 承载组件 SHARED 安装的目录归属（引用挂树，§十二-7）：仅组织视角，与副本所有权解耦；
--   null=未归组（落 ungrouped）；挪动引用叶子＝更新本列，绝不动源组件行的 directory_id。
-- ----------------------------
drop table if exists databus_install;
create table databus_install (
    id                  bigint(20)   not null                  comment '主键id',
    owner_id            bigint(20)   not null                  comment '安装者id（owner）',
    market_item_id      bigint(20)   default null              comment '市场物料id（source；FAVORITE 语义可空）',
    local_resource_id   bigint(20)   not null                  comment '本地副本id（链路主键 / 组件DB行id）',
    mode                varchar(16)  not null                  comment '安装模式（FORK / SHARED / FAVORITE）',
    directory_id        bigint(20)   default null              comment '目录归属（组件SHARED引用挂树的组织视角，null=未归组）',
    content_hash        varchar(64)  default null              comment '落快照时的逻辑源码指纹（md5）',
    create_time         datetime     default current_timestamp comment '安装时间',
    primary key (id),
    unique key uk_install (owner_id, market_item_id),
    key idx_install_owner (owner_id),
    key idx_install_market (market_item_id),
    key idx_install_local (local_resource_id)
) engine=innodb comment='物料市场安装记录（owner→source 血缘，fork/shared 引用）';

-- ----------------------------
-- 菜单与权限：正本已收敛至 script/sql/databus_menu.sql
--   （2026-10-23 tabs 收敛终态，全量重建型：开头按三段 id 范围清旧、再全量 insert，
--   1 条 M 目录 20000 + 54 条 F 行直接挂靠，含 5 条 list 载体行；本脚本只管 DDL 与种子数据）
-- ----------------------------

-- ----------------------------
-- 种子数据：把代表性系统资源上架为 ONLINE，冷启动即有货（§十四；幂等，可重复执行）
-- owner 取超管（user_name=admin，其 user_id 为雪花段 1761100000000000001，不可硬编码为 1）；
-- 固定 id 段先清后插：组件 1761400000000030001~30099，链路 1761400000000031001~31099。
-- 源取「启用且未废弃」的组件（按 sort,id 升序取前 8）与「已发布非模板」的链路（下方精选 6 条）；
-- 已存在同源上架记录（含手工上架）的源自动跳过，避免撞 uk_market_source。
-- 逐条语句无中文常量（名称/简介等一律取自源表），规避本机客户端「多行+中文」的 1264。
-- ----------------------------
delete from databus_market_item where id between 1761400000000030001 and 1761400000000031099;
set @mi_owner := (select user_id from sys_user where user_name = 'admin' limit 1);

set @mi_rn := 1761400000000030000;
insert into databus_market_item
    (id, owner_id, owner_code, resource_type, source_id, code_display, name, summary, icon, color,
     category_id, tags, status, install_count, ref_count, fork_publish_count, create_time, update_time, remark)
select
    (@mi_rn := @mi_rn + 1) as id,
    @mi_owner, 'admin', 'COMPONENT', c.id,
    concat('admin/', c.component_code),
    c.component_name, c.description, substr(c.icon, 1, 64), c.color,
    null, null, 'ONLINE', 0, 0, 0, sysdate(), sysdate(), null
from databus_component c
where c.status = '0' and c.deprecated = '0' and c.del_flag = '0'
  and not exists (select 1 from databus_market_item m
                  where m.owner_id = @mi_owner and m.resource_type = 'COMPONENT' and m.source_id = c.id)
order by c.sort, c.id
limit 8;

-- 链路货源冷启动：mock 链路默认全为草稿（status=0），市场链路种子只取「已发布非模板」，
-- 故此处先精选 6 条「纯本地逻辑示例」置为已发布（status='1'），供下方种子挑选；
-- 仅挑逻辑示例（排除 bpm-* 需外部环境 / alert-test-* 告警用例），幂等可重复执行。
-- 注意：此处仅改市场展示所需的发布态，不推 Rule-DB（正式执行仍走链路工作台发布）。
update databus_chain
set status = '1'
where is_template = '0' and del_flag = '0'
  and chain_code in ('serial-all', 'if-empty-false', 'while-loop', 'iterator-nested', 'and-logic', 'or-logic');

set @mi_rn := 1761400000000031000;
insert into databus_market_item
    (id, owner_id, owner_code, resource_type, source_id, code_display, name, summary, icon, color,
     category_id, tags, status, install_count, ref_count, fork_publish_count, create_time, update_time, remark)
select
    (@mi_rn := @mi_rn + 1) as id,
    @mi_owner, 'admin', 'CHAIN', ch.id,
    concat('admin/', ch.chain_code),
    ch.chain_name, ch.remark, null, null,
    null, null, 'ONLINE', 0, 0, 0, sysdate(), sysdate(), null
from databus_chain ch
where ch.status = '1' and ch.is_template = '0' and ch.del_flag = '0'
  and ch.chain_code in ('serial-all', 'if-empty-false', 'while-loop', 'iterator-nested', 'and-logic', 'or-logic')
  and not exists (select 1 from databus_market_item m
                  where m.owner_id = @mi_owner and m.resource_type = 'CHAIN' and m.source_id = ch.id)
order by ch.id;

-- ----------------------------
-- 种子数据：市场分类 databus_market_category（分类口径＝B：由本表承载，运营可增删改名；§四）
-- 冷启动先播组件五分类（与 databus_component.category 枚举一一对应，经运营在后台扩展）。
-- 固定 id 段 1761400000000040001~40099，先清后插，幂等可重复执行。
-- 链路货源已由上方精选 6 条示例链路提供（分类暂留空），待运营按需增补链路业务域分类。
-- ----------------------------
delete from databus_market_category where id between 1761400000000040001 and 1761400000000040099;

insert into databus_market_category values (1761400000000040001, 0, '协议类（HTTP 等）', 1);
insert into databus_market_category values (1761400000000040002, 0, '数据处理', 2);
insert into databus_market_category values (1761400000000040003, 0, '流程控制', 3);
insert into databus_market_category values (1761400000000040004, 0, '人工智能', 4);
insert into databus_market_category values (1761400000000040005, 0, '平台对接（BPM 等）', 5);

-- 已上架组件的分类回填：按源表 category 枚举映射到上面五个分类 id（无中文常量）
update databus_market_item m
    join databus_component c on m.source_id = c.id
set m.category_id = case c.category
        when 'PROTOCOL' then 1761400000000040001
        when 'DATA' then 1761400000000040002
        when 'FLOW_CONTROL' then 1761400000000040003
        when 'AI' then 1761400000000040004
        when 'PLATFORM' then 1761400000000040005
        else m.category_id
    end
where m.resource_type = 'COMPONENT' and m.category_id is null;