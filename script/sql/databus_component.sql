-- ----------------------------
-- 数据总线组件元信息表 databus_component
-- 阶段 1B 建表，2026-09-22 按设计文档要求沉淀完整 DDL。
-- 三层物料模型第一层（组件定义）的落库表：
--   - componentCode 与 @LiteflowComponent value 一一对应（如 httpRequest/bpmCreateBo/condition）
--   - paramSchema 供配置面板动态渲染（JSON Schema 格式，未来 schema-driven 改造的载体）
--   - inputSchema / outputSchema 供连线 Schema 校验
--   - category 五大类（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM）
-- 当前阶段（阶段 2）组件列表仍在前端 cmp-defs.ts 硬编码，本表为 schema-driven 阶段（roadmap 阶段 3 物料市场）做预留；
--   物料市场阶段会把内置 16 个组件 + 用户物料的元数据注册到本表，前端从 /databus/component/options 拉取统一管理。
-- status（0启用 1停用）与 databus_chain 的 status（0草稿 1已发布 2已下线）语义不同，注意区分。
-- ----------------------------
drop table if exists databus_component;
create table databus_component (
    id                bigint(20)      not null                  comment '主键id',
    component_code    varchar(64)     not null                  comment '组件编码（与 @LiteflowComponent value 一一对应）',
    component_name    varchar(100)    not null                  comment '组件名称（用户可读）',
    category          varchar(32)     not null                  comment '组件分类（PROTOCOL/DATA/FLOW_CONTROL/AI/PLATFORM）',
    icon              varchar(100)    default null              comment '组件图标（前端展示用）',
    description       varchar(500)    default null              comment '组件描述',
    param_schema      text            default null              comment '参数 Schema（JSON Schema 格式，配置面板动态渲染用）',
    input_schema      text            default null              comment '输入 Schema（连线校验用）',
    output_schema     text            default null              comment '输出 Schema（连线校验用）',
    status            char(1)         default '0'               comment '状态（0启用 1停用）',
    create_dept       bigint(20)      default null              comment '创建部门',
    create_by         bigint(20)      default null              comment '创建者',
    create_time       datetime        default null              comment '创建时间',
    update_by         bigint(20)      default null              comment '更新者',
    update_time       datetime        default null              comment '更新时间',
    remark            varchar(500)    default null              comment '备注',
    del_flag          char(1)         default '0'               comment '删除标志（0存在 1删除）',
    primary key (id),
    unique key uk_component_code (component_code),
    key idx_category (category)
) engine=innodb comment='数据总线组件元信息表';
