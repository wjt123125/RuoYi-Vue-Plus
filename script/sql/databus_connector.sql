-- ----------------------------
-- 连接器资源化脚本 databus_connector.sql（2026-10-23 万物皆包，databus-material-market.md §十五）
-- 承载连接器运行层台账（与市场层 databus_market_item 解耦，§15.4 两层状态机）：
--   ① databus_connector_def  一行＝一个连接器类型（type 全局唯一），管运行层
--      ENABLED / SUSPENDED / DEPRECATED；manifest 快照驱动连接管理页动态表单
-- 内置连接器（Spring @Component 扫描注册，如 bpmHttp）不入本表：
--   GET /databus/connector/defs 为「内置 ∪ def 行」合并视图（内置视为 ENABLED，见 Service）。
--
-- DDL 一律 drop + 全量重建（本库惯例，不写 alter）。本表为新建表，无历史数据迁移。
-- 菜单与权限：正本统一在 script/sql/databus_menu.sql（连接器四 F 行 20025~20028，order 46~49）。
-- ----------------------------

-- ----------------------------
-- 连接器运行层台账 databus_connector_def
-- type 全局唯一（卸载保护范围）：同 type 传新版本＝热更新本行（version/package_path/manifest_snapshot
--   刷新，status 保持）；jvm 档「双版本并存」是 PluginClassLoader 加载层行为（§15.5），
--   数据层 v1 单行承载（15.9 落地顺序第 4 步再演进）。
-- manifest_snapshot 存 zip 内 manifest.json 原文（text）：type/label/icon/version/engine/
--   configSchema/operations（字段对齐 ConnectorDescriptor + engine/version，§15.1）。
-- source：UPLOAD（市场 tab 内上传入口）/ MARKET（enable 型安装分支）/ AI / ADMIN。
-- 精简列：create_by + create_time/update_time，无 update_by、无逻辑删除（卸载即物理删行+删包）。
-- ----------------------------
drop table if exists databus_connector_def;
create table databus_connector_def (
    id                  bigint(20)   not null                  comment '主键id',
    type                varchar(64)  not null                  comment '类型键（全局唯一；Connector#getType 对齐，卸载保护范围）',
    version             varchar(32)  not null                  comment '版本号（manifest 声明；同 type 升版本＝热更新）',
    engine              varchar(16)  not null                  comment '引擎档（http / jvm / native，§15.2 光谱）',
    manifest_snapshot   text                                   comment 'manifest.json 原文快照（驱动表单动态渲染与物料生成）',
    package_path        varchar(255) default null              comment 'zip 落盘目录（plugins/{type}/{version}）',
    status              varchar(16)  not null                  comment '运行层状态（ENABLED / SUSPENDED / DEPRECATED）',
    source              varchar(16)  not null                  comment '来源（MARKET / UPLOAD / AI / ADMIN）',
    create_by           bigint(20)   default null              comment '上传者id',
    create_time         datetime     default null              comment '创建时间',
    update_time         datetime     default null              comment '更新时间',
    primary key (id),
    unique key uk_connector_type (type),
    key idx_connector_status (status)
) engine=innodb comment='连接器运行层台账（万物皆包：管 ENABLED/SUSPENDED/DEPRECATED，与市场层状态机解耦）';
