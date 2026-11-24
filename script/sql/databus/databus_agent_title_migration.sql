-- ----------------------------
-- 存量库迁移：会话标题列改名
--
-- 背景：自动标题与用户改名共用同一个标题字段，「写入即定稿」（非空不再覆盖）。
--   旧列名 custom_title 表达的是「用户自定义」，与语义不符，故改名为 title。
--
-- 只对已经建过 databus_agent_conversation_meta 的存量库执行一次；
--   按新的 databus_agent.sql 新建的库已经是 title，不要重复执行本文件。
-- 旧数据（截断问句当标题）不需要迁移：清空会话后重新生成即可。
-- ----------------------------
alter table databus_agent_conversation_meta
    change column custom_title title varchar(64) default null
        comment '会话标题：自动生成或用户改名；null=未命名，写入后不再覆盖';
