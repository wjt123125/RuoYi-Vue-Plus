-- ----------------------------
-- 组件↔连接器依赖关系表 databus_component_connector_dep.sql
-- 2026-10-30 阶段 B 第 9 步新建（施工依据 docs/databus-jdbc-connector-plan.md §10.2 ③；
--   设计出处 docs/databus-material-market.md §15.12.3 / §15.12.5 / §15.12.7 / §15.12.9）。
--
-- 内容：一张关系表 DDL，**不含 seed**——存量 15 行依赖灌在 databus_component_seed.sql ③ 段。
--   两文件是两种幂等模式（本文件 drop + 全量重建，seed 文件 insert ignore），
--   material-market §15.12.8 明确要求分开写、别混在一起。
-- 目的：让「停用 / 卸载某个连接器」能一次 SQL 反查出影响面（哪些组件、进而哪些链），
--   给确认框一个数字（§15.12.5：只提示，不拦；运行期缺依赖自然报错，在预期之内）。
--
-- ★ 与 material-market §15.12.3 的一处有意偏离：列名 connector_type → connector_family
--   上游 DDL 写的是 connector_type，但本仓库存进去的值是**能力族**、不是具体 type。
--   正本 §8.3：「注意是 family 不是 type：组件只认『我要一个 SQL 连接器』，具体是
--   PostgreSQL 还是 Oracle 由连接行自己决定」。而 connector_type 这个名字在本库已被占用为
--   「具体 type」——sys_databus_connection.connector_type 与 databus_connector_def.type
--   存的都是 jdbc-postgresql 这一级。同名不同义会让 §15.12.5 的反查写成
--   where connector_type = 'jdbc-postgresql' 而恒返 0 行：静默错，比报错更难查。
--   建表时全仓库对本表零引用（Mapper 未落地），改名零成本，故取名实相符的 connector_family。
--   反查路径随之固定为：def.type → manifest_snapshot 里的 family → 本表 connector_family
--   （family 不落 def 表独立列，只活在 manifest_snapshot JSON 内，见 databus_connector_def.sql）。
--
-- 红线①（只覆盖库内组件）：内置 jar 件不进 databus_component，故不在本表，反查影响面会漏掉它们
--   （§15.12.9-1，靠运行期报错兜底）。连接器零内置 + 组件全量脚本化后该盲区自然消失。
-- 红线②（与 param_schema 双写）：本表每行都等价于 databus_component.param_schema 里某个
--   CONNECTION_SELECT 字段的 connectorFamily（§15.12.9-2，本方案唯一双写点）。
--   运行时写入点四处见 §15.12.7（脚本保存/回滚、表单件新建、表单件编辑、组件删除）；
--   漏写一处不会报错，只会让影响面数字偏小。
-- 红线③（软退役不删行）：组件 deprecated=1 时依赖行必须留着（老链还在跑、还在用那个连接器），
--   只有硬删组件才连带删行（§15.12.7-4）。
--
-- 幂等：DDL drop + create（本库惯例，不写 alter）；本文件不含任何 insert。
-- 无 tenant_id：整个 databus 模块不分租户（§15.12.3，component_code 是全局唯一键）。
-- 规模：组件数量级，不是「组件数 × 用户数」——组件安装是共享注册不落副本（§15.12.3）。
-- 零新菜单：本表只被服务端反查用，无独立页面、无新权限串。
-- ----------------------------
set names utf8mb4;

drop table if exists databus_component_connector_dep;
create table databus_component_connector_dep (
    id                bigint(20)      not null                  comment '主键id',
    component_code    varchar(64)     not null                  comment '组件编码（对应 databus_component.component_code）',
    connector_family  varchar(64)     not null                  comment '连接器能力族（对应 ConnectorDescriptor.family，如 jdbc / bpmHttp；不是具体 type，理由见文件头偏离说明）',
    create_time       datetime        default null              comment '创建时间',
    primary key (id),
    unique key uk_code_family (component_code, connector_family),
    key idx_connector_family (connector_family)
) engine=innodb comment='组件↔连接器依赖关系表（只存数据库里的组件，内置 jar 件不在内）';
