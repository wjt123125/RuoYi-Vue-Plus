-- ----------------------------
-- 连接器资源化脚本 databus_connector.sql（2026-10-23 万物皆包，databus-material-market.md §十五）
-- 承载连接器运行层台账（与市场层 databus_market_item 解耦，§15.4 两层状态机）：
--   ① databus_connector_def  一行＝一个连接器类型（type 全局唯一），管运行层
--      PENDING / ENABLED / DISABLED / DEPRECATED；manifest 快照驱动连接管理页动态表单
-- DDL 一律 drop + 全量重建（本库惯例，不写 alter）。本表为新建表，无历史数据迁移。
-- 菜单与权限：正本统一在 script/sql/databus_menu.sql（连接器四 F 行 20025~20028，order 46~49）。
-- ----------------------------

-- ----------------------------
-- 连接器运行层台账 databus_connector_def
-- 身份键 = (type, version)：不同 version 是不同连接器，各自独立审核/启用/上架/安装/卸载，
--   同 type 多版本并存互不影响；只有 (type, version) 相同且 build_no 更大才是「同版本升级」。
--   build_no：同版本升级（替换包内容）的唯一判据，不填＝0。
-- manifest_snapshot 存 zip 内 manifest.json 原文（text）：type/label/icon/version/engine/
--   configSchema/operations（字段对齐 ConnectorDescriptor + engine/version，§15.1）。
-- source：UPLOAD（市场 tab 内上传入口）/ MARKET（enable 型安装分支）/ AI / ADMIN / DEV（dev 目录装载）。
-- owner_type：SYSTEM（系统资源，仅治理角色可上架）/ USER（用户资源，仅作者可上架）。
--   连接器零内置，UPLOAD 行恒为 USER；DEV 行虽由启动时装载，但归属开发者，亦为 USER。
--   上架权限以 owner_type 为准，不依赖 create_by（审计字段仅作审计用途）。
-- 精简列：create_by + create_time/update_time，无 update_by、无逻辑删除（卸载即物理删行+删包）。
-- ----------------------------
drop table if exists databus_connector_def;
create table databus_connector_def (
    id                  bigint(20)   not null                  comment '主键id',
    type                varchar(64)  not null                  comment '类型键（Connector#getType 对齐；与 version 一起构成身份）',
    version             varchar(32)  not null                  comment '版本号（manifest 声明；不同 version＝不同连接器）',
    build_no            int(11)      not null default 0        comment '构建号（同 type+version 下更大＝同版本升级）',
    engine              varchar(16)  not null                  comment '引擎档（http / jvm / native，§15.2 光谱）',
    manifest_snapshot   text                                   comment 'manifest.json 原文快照（驱动表单动态渲染与物料生成）',
    package_path        varchar(255) default null              comment 'zip 落盘目录（plugins/{type}/{version}）',
    status              varchar(16)  not null                  comment '运行层状态（PENDING / ENABLED / DISABLED / DEPRECATED）',
    source              varchar(16)  not null                  comment '来源（MARKET / UPLOAD / AI / ADMIN / DEV）',
    owner_type          varchar(16)  not null default 'USER'   comment '归属类型（SYSTEM 系统资源/USER 用户资源；上架权限判据，2026-11-05 增）',
    verified            tinyint(1)   not null default 0        comment '信任档（审批人审核时勾选，§1.3；true 放开试连异常回显与私网豁免）',
    create_by           bigint(20)   default null              comment '上传者id',
    create_time         datetime     default null              comment '创建时间',
    update_time         datetime     default null              comment '更新时间',
    primary key (id),
    unique key uk_connector_type_version (type, version),
    key idx_connector_type (type),
    key idx_connector_status (status)
) engine=innodb comment='连接器运行层台账（万物皆包：管 PENDING/ENABLED/DISABLED/DEPRECATED，与市场层状态机解耦）';
