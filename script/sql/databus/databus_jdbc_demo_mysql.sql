-- ----------------------------
-- JDBC 连接器实测靶库（MySQL）：独立库 + 专用账号 + 覆盖类型矩阵的测试表
-- 正本 docs/databus-jdbc-connector-plan.md §十一 阶段 B 验证清单的落地料；
-- 与 sys_databus_connection 里 connection_id='jdbc-mysql-demo' 的种子行配套（同目录 databus_connection.sql）。
--
-- 为什么单独一个库：平台自己的库是 ry-vue，JDBC 连接器测的是「平台之外的第三方库」，
-- 混在 ry-vue 里既污染平台表，也测不出 child-first 装载与独立建池。
--
-- 可重复执行：库用 if not exists，表用 drop + create，账号用 create if not exists + alter。
-- 执行方式（PowerShell 下密码必须 --password=xxx 长格式；source 路径必须纯正斜杠、遇错即停）：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=<你的root密码> --default-character-set=utf8mb4 -e "source e:/01.code/RuoYi-Vue-Plus/script/sql/databus_jdbc_demo_mysql.sql"
-- ----------------------------
set names utf8mb4;

-- ----------------------------
-- 专用账号：必须 mysql_native_password
-- 原因：MYSQL 的参数黑名单里含 allowPublicKeyRetrieval（它能被用来做中间人拿 RSA 公钥，
-- 属高危键），而 MySQL 8 默认的 caching_sha2_password 在**非 TLS** 连接下必须靠这个参数
-- 才能完成首次认证——黑名单一禁，caching_sha2_password 账号就直接连不上。
-- 本机是 MySQL 8.0（8.4 起该插件被移除），故用 native password 最省事。
-- 若将来上 8.4/9.x：改成 caching_sha2_password，并在连接的 driverProps 里给 sslMode=REQUIRED 走 TLS。
-- ----------------------------
create database if not exists databus_jdbc_demo default character set utf8mb4 collate utf8mb4_general_ci;

create user if not exists 'databus_demo'@'%' identified with mysql_native_password by 'Databus@Demo1';
alter user 'databus_demo'@'%' identified with mysql_native_password by 'Databus@Demo1';
-- 权限只给这一个库：连接配置里的 readOnly 只是弱防线，账号权限才是主防线（正本 §9.1）。
-- 给到 DDL 是为了 dbExecute 能测 DDL 分支；不想测就把 create/drop/alter 从 grant 里去掉。
grant select, insert, update, delete, create, drop, alter, index on databus_jdbc_demo.* to 'databus_demo'@'%';
flush privileges;

use databus_jdbc_demo;

-- ----------------------------
-- 表一：类型矩阵靶表（对齐 JdbcResultMapper §7.1 的每一行分支）
-- 用法：select * from jdbc_type_matrix  →  逐列比对 JSON 输出的类型与格式
-- ----------------------------
drop table if exists jdbc_type_matrix;
create table jdbc_type_matrix (
    id           bigint        not null auto_increment comment '自增主键（测 BIGINT + generatedKeys 回填）',
    tag          varchar(32)   not null                comment '行标记',
    c_char       char(10)      default null            comment 'CHAR → String',
    c_varchar    varchar(100)  default null            comment 'VARCHAR → String（含中文，验字符集）',
    c_text       text          default null            comment 'TEXT → LONGVARCHAR → String',
    c_tinyint    tinyint       default null            comment 'TINYINT → int',
    c_smallint   smallint      default null            comment 'SMALLINT → int',
    c_int        int           default null            comment 'INTEGER → int',
    c_bigint     bigint        default null            comment 'BIGINT → long；超 2^53 转 String',
    c_float      float         default null            comment 'REAL/FLOAT → double',
    c_double     double        default null            comment 'DOUBLE → double',
    c_decimal    decimal(18,4) default null            comment 'DECIMAL → BigDecimal；decimalAsString 默认 true → 字符串',
    c_date       date          default null            comment 'DATE → yyyy-MM-dd',
    c_time       time          default null            comment 'TIME → HH:mm:ss',
    c_datetime   datetime      default null            comment 'DATETIME → yyyy-MM-dd HH:mm:ss（不带 T/Z）',
    c_timestamp  timestamp     null default null       comment 'TIMESTAMP → yyyy-MM-dd HH:mm:ss',
    c_bool       boolean       default null            comment 'BOOLEAN（实为 tinyint(1)）→ boolean',
    c_bit        bit(8)        default null            comment 'BIT → boolean（1 位）/ 数值',
    c_binary     binary(4)     default null            comment 'BINARY → Base64 字符串',
    c_varbinary  varbinary(64) default null            comment 'VARBINARY → Base64 字符串',
    c_blob       blob          default null            comment 'BLOB → Base64；超 1MB 截断为 <truncated:N bytes>',
    c_enum       enum('A','B','C') default null        comment 'ENUM → VARCHAR → String',
    c_json       json          default null            comment 'JSON → 未映射类型，走 default 兜底 String.valueOf',
    primary key (id)
) engine=innodb default charset=utf8mb4 comment='JDBC 类型矩阵靶表';

-- 逐条 insert（本机 mysql 批量 insert 带中文曾报 1264，一条一句最稳）
insert into jdbc_type_matrix
  (tag, c_char, c_varchar, c_text, c_tinyint, c_smallint, c_int, c_bigint, c_float, c_double, c_decimal,
   c_date, c_time, c_datetime, c_timestamp, c_bool, c_bit, c_binary, c_varbinary, c_blob, c_enum, c_json)
values
  ('full', 'abc', '中文与English混排', '长文本：数据总线 JDBC 连接器类型矩阵验证', 127, 32000, 2147483647, 9223372036854775807,
   3.14, 2.718281828459045, 12345678901234.5678,
   '2026-10-31', '13:45:09', '2026-10-31 13:45:09', '2026-10-31 13:45:09', 1, b'1',
   0x41424344, 0xDEADBEEF, 0xCAFEBABE, 'B', '{"k":"v","n":1}');

-- 超 JS 安全整数（2^53=9007199254740992）→ 必须转 String，前端不丢精度
insert into jdbc_type_matrix (tag, c_bigint, c_int) values ('snowflake', 1762000000000100001, 1);

-- 全 NULL 行：验「NULL 就是 null，不转空串、不转 0」这条硬约定
insert into jdbc_type_matrix (tag) values ('all-null');

-- 空串行：验空串与 NULL 不被混同
insert into jdbc_type_matrix (tag, c_char, c_varchar, c_text) values ('empty-string', '', '', '');

-- ----------------------------
-- 表二：业务形态靶表（给测试链的 ? 占位查询用，多行结果）
-- ----------------------------
drop table if exists jdbc_org_user;
create table jdbc_org_user (
    user_id     varchar(32)   not null                comment '用户ID',
    user_name   varchar(64)   not null                comment '姓名',
    dept_id     int           not null                comment '部门ID（查询条件列）',
    salary      decimal(12,2) default null            comment '薪资',
    status      char(1)       default '0'             comment '状态（0正常 1停用）',
    remark      varchar(255)  default null            comment '备注',
    create_time datetime      default null            comment '创建时间',
    primary key (user_id),
    key idx_dept (dept_id)
) engine=innodb default charset=utf8mb4 comment='JDBC 查询靶表（组织用户）';

insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u001', '张三', 103, 12500.50, '0', '研发一部', '2026-01-05 09:00:00');
insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u002', '李四', 103, 13800.00, '0', '研发一部', '2026-01-06 09:10:00');
insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u003', '王五', 105, 9800.75, '0', '测试部', '2026-02-11 14:20:00');
insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u004', '赵六', 105, null, '1', '测试部（停用，薪资为 NULL）', '2026-02-12 14:30:00');
insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u005', '钱七', 108, 21000.00, '0', '架构组', '2026-03-01 08:00:00');
insert into jdbc_org_user (user_id, user_name, dept_id, salary, status, remark, create_time) values ('u006', '孙八', 108, 18600.25, '0', '架构组', '2026-03-02 08:30:00');

-- ----------------------------
-- 表三：写入靶表（给 dbExecute 回填自增主键、dbBatch 批量插入用）
-- 每次实测前可手工 truncate：truncate table jdbc_write_log;
-- ----------------------------
drop table if exists jdbc_write_log;
create table jdbc_write_log (
    id          bigint       not null auto_increment comment '自增主键（验 generatedKeys 回填）',
    user_id     varchar(32)  default null            comment '关联用户',
    action      varchar(32)  default null            comment '动作',
    payload     varchar(255) default null            comment '载荷',
    create_time datetime     default current_timestamp comment '写入时间',
    primary key (id)
) engine=innodb default charset=utf8mb4 comment='JDBC 写入靶表';

insert into jdbc_write_log (user_id, action, payload) values ('u001', 'init', '种子行');

-- ============================================================
-- 跑完本脚本后，当前会话库停在 databus_jdbc_demo（上面第 3 节的 use）。
-- 接着跑平台侧的 databus_connection.sql / databus_chain_mock_data.sql 前必须先切回平台库，
-- 否则它们会跑到靶库里找 sys_user / sys_databus_connection，报：
--   1146 - Table 'databus_jdbc_demo.sys_user' doesn't exist
--
--   use `ry-vue`;      -- 库名带连字符，反引号不能省
--
-- 用命令行跑则给 --database=ry-vue，不受本脚本 use 的影响。
-- ============================================================
