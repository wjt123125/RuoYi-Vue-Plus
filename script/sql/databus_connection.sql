-- ----------------------------
-- 数据总线连接管理表 sys_databus_connection
-- 1D-P0 步骤 6 建表，2026-09-18 按设计文档 §16 决策重构：
-- 通用元数据列 + config（明文 JSON）+ credentials（AES 加密 JSON），
-- 不再为每种 connector 的参数平铺加列，未来新增 connector 零 DDL。
-- 三层物料模型第二层（Connection 实例）的落库表，组件层通过 connection_id 引用。
-- 注意：credentials 列加密依赖 mybatis-encryptor.enable=true（application.yml），
--       开关关闭时功能正常但凭据以明文 JSON 落库，仅适合本地开发。
-- ----------------------------
drop table if exists sys_databus_connection;
create table sys_databus_connection (
    id                bigint(20)      not null                  comment '主键id',
    connection_id     varchar(64)     not null                  comment '连接ID（全局唯一，组件层引用）',
    connection_name   varchar(100)    not null                  comment '连接名称（用户可读）',
    connector_type    varchar(32)     not null                  comment 'Connector 类型标识（如 bpmHttp）',
    config            text            default null              comment '连接配置（明文 JSON，非敏感参数；字段 schema 由 connector describe 声明，如 endpoint/authUser/timeoutMs/retryCount/ipWhiteList）',
    credentials       text            default null              comment '凭据（AES 字段级加密 JSON，敏感字段如 authPassword/token/secretKey）',
    enabled           char(1)         default 'Y'               comment '是否启用（Y启用 N禁用，启用时执行链路自动注入 DatabusContext）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_connection_id (connection_id),
    key idx_connector_type (connector_type)
) engine=innodb comment='数据总线连接管理表';

-- ----------------------------
-- 菜单与权限（连接管理页：列表/查询/新增/修改/删除/测试连接）
-- 前端走动态路由：菜单 component 填 databus/connection/index 即自动映射 views/databus/connection/index.vue
-- ----------------------------
-- 推荐在【系统管理 → 菜单管理】UI 中新增（父菜单选已有的"数据总线"目录），字段对照如下。
-- 父菜单"数据总线"目录 menu_id=1761400000000020000（parent_id=0, menu_type=M）；
-- 若环境里该目录 ID 不同，请替换下面的父 ID，并确认 1762000000000000010~015 不与既有菜单冲突。
-- 注意：parent_id 必须指向真实存在的父菜单，否则菜单在菜单管理可见但挂不上侧边栏树（孤儿菜单不显示）。
-- 非 admin 账号还需在【系统管理 → 角色管理】勾选这 6 项菜单/按钮权限（sys_role_menu）。
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark

-- 已按旧占位父 ID（1762000000000000000）执行过、侧边栏看不到菜单的环境，先跑这句修复：
update sys_menu set parent_id = 1761400000000020000 where menu_id = 1762000000000000010;

insert into sys_menu values
  (1762000000000000010, '连接管理', 1761400000000020000, 2, 'connection', 'databus/connection/index', '', 'N', 'N', 'C', '0', '0', 'databus:connection:list', 'link', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线连接管理菜单'),
  (1762000000000000011, '连接查询', 1762000000000000010, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1762000000000000012, '连接新增', 1762000000000000010, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1762000000000000013, '连接修改', 1762000000000000010, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1762000000000000014, '连接删除', 1762000000000000010, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, ''),
  (1762000000000000015, '连接测试', 1762000000000000010, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:test', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '测试连接是否可用');

-- ----------------------------
-- BPM HTTP 连接实例种子数据（三层物料第二层：Connection 实例）
-- connection_id = bpm-default，与前端 CmpProps.vue 组件默认参数引用的 ID 对齐；
-- config 字段名与 BpmHttpConnectionCfg / BpmHttpConnector describe() 的 configSchema 对齐，
-- authPassword 属敏感字段（describe 标 sensitive），落 credentials 列。
-- 注意：手写 INSERT 不经过 MyBatis @EncryptField 加密拦截器，credentials 为明文 JSON；
--       无密文头值读取时原样返回（兼容），在连接管理页重新保存一次即转为 AES 密文。
--       endpoint/authUser/authPassword 按实际 BPM 容器环境修改。
-- ----------------------------
insert into sys_databus_connection
  (id, connection_id, connection_name, connector_type, config, credentials, enabled, create_time, del_flag, remark)
values
  (1762000000000020001, 'bpm-default', 'BPM 本地容器', 'bpmHttp',
   '{"endpoint":"http://localhost:8088","authUser":"admin","timeoutMs":30000,"retryCount":0,"ipWhiteList":[]}',
   '{"authPassword":"1"}',
   'Y', sysdate(), '0', 'BPM 端总线 app 种子连接；endpoint/账号密码按实际环境修改');
