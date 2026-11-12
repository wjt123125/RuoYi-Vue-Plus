-- ----------------------------
-- SnailAI 侧「数据总线助手」智能体种子（OpenAPI 对话用）
--
-- 背景：数据总线工作台右侧 AI 面板只经 SnailAI OpenAPI（/openapi/v1/chat）出字，
--   SnailAI 的对话责任链有两道硬校验，缺一条对话就跑不通：
--   ① SubscriptionCheckHandler：OpenAPI 调用（openId 非空）一律校验 sai_user_agent，
--      未订阅直接终止，且错误文本不是 JSON、会被桥接层丢弃 —— 前端只能看到空流；
--   ② ContextCollectorHandler：sai_agent.app_id 必须非空且对应已启用的 sai_app，
--      还要有存活客户端实例（sai_client_node），否则报「没有可用的客户端实例」。
--
-- 本脚本做三件事（不碰 id=1 的「智测先锋专家」）：
--   ① 新建本项目专用智能体「数据总线助手」，继承 sai_app.app_id='1'；
--   ② 对话模型取 sai_model_config 里第一个启用的 CHAT 模型（现库中为 glm-5.1）；
--   ③ 给所有 OpenAPI 用户补上订阅关系，另由后端首次解析 openId 时自动订阅兜底。
--
-- 幂等：固定智能体 ID，先按 ID 删会话/记录/订阅/统计再插，可重复执行。
-- ID 段：9001 起（SnailAI 后台自增 ID 目前只到 1，留高位段避免误删他人智能体）。
--
-- 执行后：把 application.yml 的 databus.agent.agent-id 从 1 改为 9001 并重启后端。
-- ----------------------------
set names utf8mb4;

-- 本项目的智能体 ID / 名称，全脚本只在这里改
set @agent_id = 9001;
set @agent_name = '数据总线助手';
-- 对话模型：取首个启用的 CHAT 模型（无模型时为 NULL，对话会报错，需先在后台配置模型）
set @chat_model_id = (
    select id from sai_model_config
    where model_type = 'CHAT' and is_enabled = 1
    order by is_default desc, id asc
    limit 1
);

-- 先删：会话记录 → 会话 → 用量统计 → 订阅 → 智能体
delete from sai_agent_conversation_record where agent_id = @agent_id;
delete from sai_agent_conversation where agent_id = @agent_id;
delete from sai_agent_usage_stat where agent_id = @agent_id;
delete from sai_user_agent where agent_id = @agent_id;
delete from sai_agent where id = @agent_id;

-- 再插：本项目专用智能体，app_id 与 sai_app 保持一致（NULL 会走本地执行，但本链路强制远程分发）
insert into sai_agent
  (id, name, description, avatar, instruction, greeting, preset_questions,
   chat_model_id, memory_enabled, mcp_enabled, skill_enabled, web_search_enabled,
   rag_enabled, rag_ids, rag_call_mode, short_term_memory_size,
   creator_id, is_featured, view_count, status, config, app_id, create_dt, update_dt)
values
  (@agent_id, @agent_name,
   '数据总线工作台内置助手：答疑、梳理链路、定位执行问题。',
   null,
   '你是数据总线平台的助手。回答要简短、直接、可执行；不确定的事实不要编造，先说明需要什么信息。
排版约定：表示固定标识（链路编码、字段名、状态值等）时只用一层反引号，例如 `serial-all`；不要再套一层反引号，也不要给代码标识额外加粗。',
   '你好，我是数据总线助手，可以帮你梳理链路、解释报错、生成脚本草稿。',
   '["帮我梳理当前链路的执行顺序","这条链路为什么执行失败？","给这条链路生成一段脚本"]',
   @chat_model_id, 0, 0, 0, 0,
   0, null, 1, 20,
   1, 0, 0, 1, null, '1', sysdate(), sysdate());

-- 订阅：给所有 OpenAPI 用户补订阅（平台用户 id 见 sai_openapi_user.platform_user_id）
insert into sai_user_agent (user_id, agent_id, create_dt)
select o.platform_user_id, @agent_id, sysdate()
from sai_openapi_user o
where o.platform_user_id is not null;

-- 自检：三行结果都应有值（model 为空说明库里没有启用的 CHAT 模型）
select a.id as agent_id, a.name, a.app_id, a.chat_model_id, m.model_name, a.status
from sai_agent a
         left join sai_model_config m on m.id = a.chat_model_id
where a.id = @agent_id;

select ua.user_id, ua.agent_id, u.username
from sai_user_agent ua
         left join sai_user u on u.id = ua.user_id
where ua.agent_id = @agent_id;

select count(*) as alive_client_cnt
from sai_client_node
where app_id = '1' and expire_dt > sysdate();