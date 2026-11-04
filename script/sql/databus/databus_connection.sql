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
    config            text            default null              comment '连接配置（明文 JSON，非敏感参数；字段 schema 由 connector describe 声明，如 endpoint/accessKey/timeoutMs/retryCount）',
    credentials       text            default null              comment '凭据（AES 字段级加密 JSON，敏感字段如 apiSecret/token/secretKey）',
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
-- 菜单与权限：正本已收敛至 script/sql/databus_menu.sql
--   （2026-10-23 tabs 收敛终态，全量重建型：开头按三段 id 范围清旧、再全量 insert，
--   1 条 M 目录 20000 + 54 条 F 行直接挂靠，含 5 条 list 载体行；本脚本只管 DDL 与连接种子数据）
-- ----------------------------

-- ----------------------------
-- BPM HTTP 连接实例种子数据（三层物料第二层：Connection 实例）
-- connection_id = bpm-default，与前端 mock-presets / CmpProps.vue 引用的 ID 对齐；
-- config 字段名与 BpmHttpConnectionCfg / BpmHttpConnector describe() 的 configSchema 对齐，
-- apiSecret 属敏感字段（describe 标 sensitive），落 credentials 列。
-- 鉴权：2026-09-20 起唯一通道 /portal/openapi（access_key + HmacMD5 签名），
--       种子值 databus/databus 为现场 CC 身份策略弱值，仅本地开发用，上线前必须轮换强随机串。
-- 注意：手写 INSERT 不经过 MyBatis @EncryptField 加密拦截器，credentials 为明文 JSON；
--       无密文头值读取时原样返回（兼容），在连接管理页重新保存一次即转为 AES 密文。
--       endpoint/access_key/apiSecret 按实际 BPM 容器环境修改。
-- 幂等：先按主键删旧种子（旧 jd 形态 config 一并清掉），再插入 openapi 形态。
-- ----------------------------
delete from sys_databus_connection where id = 1762000000000020001;
insert into sys_databus_connection
  (id, connection_id, connection_name, connector_type, config, credentials, enabled, create_time, del_flag, remark)
values
  (1762000000000020001, 'bpm-default', 'BPM 本地容器', 'bpmHttp',
   '{"endpoint":"http://localhost:8088","accessKey":"databus","timeoutMs":30000,"retryCount":0}',
   '{"apiSecret":"databus"}',
   'Y', sysdate(), '0', 'BPM 端总线 app 种子连接（OpenAPI 签名网关）；endpoint/密钥按实际环境修改');

-- ----------------------------
-- JDBC 示例连接种子（阶段 B 第 9 步，正本 docs/databus-jdbc-connector-plan.md §10.2）
-- connection_id = jdbc-default，与 databus_component_seed.sql 里 dbQuery/dbExecute/dbBatch
--   三件的 data_example 引用对齐（同 bpm-default 与 mock-presets 对齐的口径）。
-- connector_type 存**具体 type**（jdbc-postgresql），不是能力族 jdbc——族在连接器包的
--   ConnectorDescriptor.family 里，连接行只认 type，运行时由 type 找实例（正本 §8.3）。
-- config 键名与 AbstractJdbcConnector.configSchema() 对齐；urlMode 必须显式写 basic：
--   host/port/database 的 showWhen 是 ne override，键缺失时 String(null) != "override" 仍成立，
--   但显式写出可让「连接串来源」下拉在编辑态直接落到正确选项。
-- enabled = 'N'：默认停用。JDBC 连接一旦启用就会真建池出网，示例值指向 localhost:5432
--   多半连不上；改成实际库地址、并在连接管理页重存一次（转 AES）后再手工启用。
-- 安全提醒：password 属敏感字段（describe 标 sensitive），落 credentials 列；
--   同 bpm-default，手写 INSERT 不经 @EncryptField 拦截器，此处为明文 JSON，
--   在页面重存一次即转 AES 密文。建议账号只读——连接的 readOnly 只是弱防线，账号权限才是主防线。
-- 幂等：先按主键删旧种子再插入（同 bpm-default 段口径）。
-- ----------------------------
delete from sys_databus_connection where id = 1762000000000020002;
insert into sys_databus_connection
  (id, connection_id, connection_name, connector_type, config, credentials, enabled, create_time, del_flag, remark)
values
  (1762000000000020002, 'jdbc-default', 'PostgreSQL 示例库', 'jdbc-postgresql',
   '{"urlMode":"basic","host":"localhost","port":5432,"database":"databus","username":"databus"}',
   '{"password":"databus"}',
   'N', sysdate(), '0', 'JDBC 示例连接（默认停用）；依赖已上传并启用的 jdbc-postgresql 连接器包，库地址/账号按实际环境修改后在页面重存一次');

-- ----------------------------
-- JDBC 实测靶连接：MySQL（本地免安装即可跑通全机制）
-- connection_id = jdbc-mysql-demo，与 databus_chain_mock_data.sql 里 jdbc-* 测试链的
--   connectionId 引用对齐；靶库建表料见同目录 databus_jdbc_demo_mysql.sql（先跑它再跑本行）。
-- connector_type = 'jdbc-mysql'：具体 type，不是能力族 jdbc（同 jdbc-default 段口径）。
-- enabled = 'Y'：与 jdbc-default 相反，本行指向的本地 MySQL 靶库是真能连上的，
--   直接启用省一次页面操作；连接池按需惰性创建，靶库脚本没跑时也只是执行链路时报连接失败，
--   不会拖启动。不想让它出现在链路的连接下拉里就改回 'N'。
-- driverProps 两个键是必需的，不是可选装饰：
--   characterEncoding=UTF-8 —— JdbcUrlBuilder 不注入任何默认参数（只注超时与凭证），
--     字符集全靠这里给，否则中文列值可能按平台默认字符集解码成乱码；
--   connectionTimeZone=Asia/Shanghai —— 本机 MySQL 的 time_zone 多为 SYSTEM，
--     驱动解析不出会报 "The server time zone value 'CST' is unrecognized"。
--   两个键都不在 MYSQL 黑名单内，也不含通用高危字样，能正常通过 filter。
-- 密码为什么明文：手写 INSERT 不经 MyBatis @EncryptField 拦截器，无密文头值读取时原样返回；
--   在连接管理页重存一次即转 AES。账号 databus_demo 只授权 databus_jdbc_demo 一个库。
-- ----------------------------
delete from sys_databus_connection where id = 1762000000000020003;
insert into sys_databus_connection
  (id, connection_id, connection_name, connector_type, config, credentials, enabled, create_time, del_flag, remark)
values
  (1762000000000020003, 'jdbc-mysql-demo', 'MySQL 实测靶库', 'jdbc-mysql',
   '{"urlMode":"basic","host":"localhost","port":3306,"database":"databus_jdbc_demo","username":"databus_demo","driverProps":{"characterEncoding":"UTF-8","connectionTimeZone":"Asia/Shanghai"},"maxRows":500,"decimalAsString":true,"logSql":true}',
   '{"password":"Databus@Demo1"}',
   'Y', sysdate(), '0', 'JDBC 实测靶连接：先执行 script/sql/databus_jdbc_demo_mysql.sql 建库建账号，再跑本行；依赖 jdbc-mysql 连接器包（dev 走 dev-plugin-dir，生产走市场上传）');
