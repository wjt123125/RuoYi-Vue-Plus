-- ----------------------------
-- AI Agent 测试靶表（MySQL）：agent_order
-- 用途：给设计期助手做人工验收，覆盖「先查连接 → 填 connectionId → 拼查询节点 → 试跑」全链路；
--   只读工具验收点：databus_connection_search 能否返回 jdbc-mysql-demo（名称「MySQL 实测靶库」）、
--   组件摘要里 needsConnector 是否为 true。
--
-- 库与连接：复用 JDBC 实测靶库 databus_jdbc_demo，与同目录 databus_connection.sql 里
--   connection_id = jdbc-mysql-demo（已启用）配套；先跑 databus_jdbc_demo_mysql.sql，
--   本脚本只删自己的表，不动 jdbc_type_matrix / jdbc_org_user / jdbc_write_log。
--
-- 可重复执行：库用 create if not exists，表用 drop + create，数据在 create 后全量 insert。
-- 执行方式（PowerShell 下密码必须 --password=xxx 长格式；source 路径必须纯正斜杠）：
--   mysql --host=127.0.0.1 --port=3306 --user=root --password=<你的root密码> --default-character-set=utf8mb4 -e "source e:/01.code/RuoYi-Vue-Plus/script/sql/databus/databus_agent_demo_mysql.sql"
-- ----------------------------
set names utf8mb4;

create database if not exists databus_jdbc_demo default character set utf8mb4 collate utf8mb4_general_ci;

use databus_jdbc_demo;

-- ----------------------------
-- 业务订单表：字段刻意留出「能出异常的列」与「能过滤的时间列」，
-- 让 agent 的校验节点、条件节点、文本拼接节点都有真实数据可用。
-- ----------------------------
drop table if exists agent_order;
create table agent_order (
    id            bigint        not null auto_increment comment '自增主键',
    order_no      varchar(32)   not null                comment '订单号（业务唯一，可作关键词）',
    customer_name varchar(64)   not null                comment '客户姓名',
    customer_phone varchar(32)  default null            comment '客户手机号（测脱敏与字符串处理）',
    amount        decimal(12,2) default null            comment '订单金额：含 NULL / 0 / 负数 三种异常形态',
    status        varchar(16)   default null            comment '状态：PAID/UNPAID/REFUNDED/CANCELLED（含金额异常但已支付的脏数据）',
    pay_time      datetime      default null            comment '支付时间（未支付为 NULL）',
    create_time   datetime      not null                comment '创建时间：含 2024 年数据，用于测时间过滤',
    remark        varchar(500)  default null            comment '备注：含长文本与 emoji，测截断与字符集',
    primary key (id),
    unique key uk_order_no (order_no),
    key idx_status (status),
    key idx_create_time (create_time)
) engine=innodb default charset=utf8mb4 comment='AI Agent 测试靶表（订单）';

-- 逐条 insert（本机 mysql 批量 insert 带中文曾报 1264，一条一句最稳）
-- 正常单：金额合法、已支付，应通过校验
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260105001', '张三', '13800000001', 120.00, 'PAID', '2026-01-05 10:01:00', '2026-01-05 09:58:00', '正常订单');
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260105002', '张三', '13800000001', 88.80, 'PAID', '2026-01-05 11:20:00', '2026-01-05 11:15:00', '同一客户第二单，可测按人聚合');
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260301001', '钱七', '13800000005', 9999.99, 'PAID', '2026-03-01 08:30:00', '2026-03-01 08:00:00', '大额订单');

-- 异常单 1：金额为 NULL（校验节点应挑出来）
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260212001', '李四', '13800000002', null, 'PAID', '2026-02-12 14:30:00', '2026-02-12 14:20:00', '金额空值脏数据');

-- 异常单 2：金额为 0
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260212002', '王五', '13800000003', 0.00, 'PAID', '2026-02-12 15:10:00', '2026-02-12 15:00:00', '零元单');

-- 异常单 3：金额为负
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260212003', '赵六', '13800000004', -50.00, 'PAID', '2026-02-12 16:05:00', '2026-02-12 16:00:00', '负数金额脏数据');

-- 未支付：pay_time 为 NULL，测空值分支
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260301002', '孙八', '13800000006', 88.80, 'UNPAID', null, '2026-03-01 09:00:00', '未支付');

-- 退款单
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260401002', '吴十', '13800000007', 66.60, 'REFUNDED', '2026-04-01 10:00:00', '2026-04-01 09:50:00', '已退款');

-- 状态为空：测 status 缺值兜底
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260401003', '陈十一', null, 500.00, null, null, '2026-04-01 11:00:00', '状态缺失');

-- 2024 年历史单：用于「create_time 早于 2025-01-01 过滤掉」这类条件测试
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20241220001', '周九', '13800000008', 1200.00, 'PAID', '2024-12-20 10:00:00', '2024-12-20 09:30:00', '历史单，应被 2025 年过滤条件排除');

-- 长备注 + emoji：测文本截断与 utf8mb4 字符集
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260501001', '郑一', '13800000009', 300.50, 'PAID', '2026-05-01 09:00:00', '2026-05-01 08:50:00',
        '客户要求发票抬头写全称并附备注：这是一段用于测试长文本截断的说明，重复填充以拉长长度——数据总线测试数据 0123456789 0123456789 0123456789 0123456789 0123456789 0123456789 🚀');

-- 小额单
insert into agent_order (order_no, customer_name, customer_phone, amount, status, pay_time, create_time, remark)
values ('SO20260501002', '冯二', '13800000010', 0.01, 'PAID', '2026-05-01 10:00:00', '2026-05-01 09:55:00', '一分钱订单');

-- ----------------------------
-- 自检：应返回 11 行；金额异常（NULL / 0 / 负数）3 行；2024 年 1 行。
--   select count(*) from agent_order;
--   select order_no, amount from agent_order where amount is null or amount <= 0;
--   select order_no from agent_order where create_time < '2025-01-01';
-- ----------------------------

-- ============================================================
-- 跑完本脚本后当前会话库停在 databus_jdbc_demo（上面的 use）。
-- 接着跑平台侧脚本（databus_connection.sql / databus_agent_seed.sql 等）前先切回平台库：
--   use `ry-vue`;      -- 库名带连字符，反引号不能省
-- ============================================================