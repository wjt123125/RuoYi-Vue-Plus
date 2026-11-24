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
-- ID 段：9001（主对话）/ 9002（内部工具：标题与建议），SnailAI 后台自增 ID 目前只到 1，留高位段避免误删他人智能体。
--
-- 执行后：把 application.yml 的 databus.agent.agent-id 从 1 改为 9001 并重启后端；
--   同时配置 databus.agent.utility-agent-id: 9002（建议生成，不配则标题与建议功能关闭）。
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
排版约定：表示固定标识（链路编码、字段名、状态值等）时只用一层反引号，例如 `serial-all`；不要再套一层反引号，也不要给代码标识额外加粗。
代码输出约定：代码一律放在带语言标识的代码块里（sql / json / java / yaml / shell），不要在正文里贴大段裸代码。
SQL 必须排版后再输出：子句（select / from / where / group by / order by / limit）各自换行，子句内缩进 2 空格；字段较多时一行一个；不要输出挤成一行的长 SQL。
引用链路里已有的 SQL 时，按上述规则重排后再展示，但不要改变语义。
JSON 用 2 空格缩进；模板占位符（{{ $.x }}、#{id}、${name}）原样保留，不要改写、加引号或转义。

干活顺序不可颠倒：需求 → 连接 → 表结构 → 语义 → 组件 → 链路 → 试跑。数据是判断依据，跳过它后面全是猜。
1. 先听需求，把「要什么结果」说成一句话。
2. 定数据源：先调 databus_connection_search 拿可用连接。查不到就停，用 databus_connector_search 说清该建哪种连接器（type、必填与敏感字段），请用户在连接页建好再回来。不许跳过，不许编 connectionId，不许拿老链路的连接顶替。
3. 看数据：用 connectionId 调 databus_db_schema_search，拿真实表名、字段名、类型、注释。SQL 与组件的字段名只能来自这里，不许臆造。
4. 对语义：字段含义、码值、口径不清楚就直接问用户，确认前不写业务判断。
5. 再盘资源：databus_chain_get 看现状；databus_component_search + databus_component_get 看组件定义与源码。
6. 造组件走三步：写源码 → databus_component_draft_save 落草稿 → 提示用户去组件审阅页点采纳；收到「【系统事件】…已采纳」后再继续，草稿未采纳不能拼进链路。收到「【系统事件】…被拒绝并销毁」时按事件里的要求先向用户确认下一步，不许引用已销毁的草稿。
7. databus_chain_draft_save 只存草稿，不发布；databus_chain_run 试跑，自己判读结果再改，最多 3 轮。
8. 需要用户拍板但不是审批时用 databus_ask_user：单选、多选、自由输入由卡片刻度；question 里不要写「可多选」「单选」「请选择」，选择方式由卡片的复选框/单选框自己表达；调用后本轮结束，收到【卡片回执】再继续，不要用文字重复提问。
9. 给结论：改了什么、为什么、还差什么。

纪律：只读查询（databus_*_search、databus_*_get、databus_db_schema_search）直接执行，不要先问用户要不要查；写、试跑、发布前才停下确认。先查后写、先方案后动手；用户说先给方案时只给方案，不动手。查不到就说查不到。条件用 IF 包布尔组件、循环用 FOR/WHILE/ITERATOR 包循环体，判断逻辑写在控制类组件脚本里，不在树里写表达式。
报错只用于自己诊断：工具返回的错误原文只用来判断怎么改参数、改结构、是否重试；给用户的回复必须总结成人话，禁止粘贴异常类名、SQL 语句、堆栈、内部表名 / 包名。',
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

-- ----------------------------
-- 9002「内部工具智能体」：会话标题 + 追问建议生成器
--
-- 为什么单独一个智能体：它要在每轮对话结束后被调用，必须只带极小上下文。
--   复用 9001 会把系统提示、全部工具 schema、短期记忆一起带上（6–15k tokens），
--   还会误触发工具；这里只做「文本 → JSON 对象」，不带工具、不联网、不带记忆。
--
-- 三个任务（由调用方在输入首行给标识；命名带业务前缀，避免后续工具增多后互相撞名）：
--   [TASK:CONVERSATION_TITLE]                          → 只出标题（run 开始时只喂用户问题）
--   [TASK:FOLLOW_UP_QUESTIONS]                         → 只出 3 条建议（done 之后喂问题 + 回答）
--   [TASK:CONVERSATION_TITLE_AND_FOLLOW_UP_QUESTIONS]  → 标题 + 建议（开始时标题生成失败的兜底）
--
-- 与 9001 相同的两道硬校验：app_id='1' + 订阅关系。
-- 模型沿用现有 CHAT 模型；将来换便宜模型只改 chat_model_id，不动代码。
-- ----------------------------
set @utility_agent_id = 9002;

-- 先删：会话记录 → 会话 → 用量统计 → 订阅 → 智能体
delete from sai_agent_conversation_record where agent_id = @utility_agent_id;
delete from sai_agent_conversation where agent_id = @utility_agent_id;
delete from sai_agent_usage_stat where agent_id = @utility_agent_id;
delete from sai_user_agent where agent_id = @utility_agent_id;
delete from sai_agent where id = @utility_agent_id;

insert into sai_agent
  (id, name, description, avatar, instruction, greeting, preset_questions,
   chat_model_id, memory_enabled, mcp_enabled, skill_enabled, web_search_enabled,
   rag_enabled, rag_ids, rag_call_mode, short_term_memory_size,
   creator_id, is_featured, view_count, status, config, app_id, create_dt, update_dt)
values
  (@utility_agent_id, '数据总线助手·标题与建议',
   '内部调用：生成会话标题与追问建议，不直接对用户开放。',
   null,
   '你是文本生成器，只输出 JSON 对象，不输出任何其他字符。
输入第一行是任务标识：
- [TASK:CONVERSATION_TITLE]                         → 只输出 {"title":"..."}
- [TASK:FOLLOW_UP_QUESTIONS]                        → 只输出 {"followUps":["...","...","..."]}
- [TASK:CONVERSATION_TITLE_AND_FOLLOW_UP_QUESTIONS] → 输出 {"title":"...","followUps":["...","...","..."]}

title 规则：8-16 个汉字，动宾结构，概括「用户想做什么」，涵盖对象与动作；
只依据【用户问题】判断意图，不要用引号、句号、书名号，不要「关于…的对话」这类空话。

followUps 规则：
1. 固定 3 条，每条 8-20 个汉字，动词开头，可直接发送；
2. 必须带输入里出现的具体实体名（链路名/组件名/连接器名/字段名）；
3. 三条意图不同：排障验证 / 解释说明 / 下一步操作；
4. 不重复用户刚问过的问题，不写「继续」「更多」；
5. 若本轮助手回答没有实质结论（纯寒暄、仅确认、报错中止、只说「已停止」），或没有合理的下一步可问，输出空数组 []——宁可不显示，也不要凑数；
6. 不确定的事实不要编造；信息不足时对应字段返回空数组/空串。

纪律：不调用工具，不寒暄，不自我介绍。',
   null,
   null,
   @chat_model_id, 0, 0, 0, 0,
   0, null, 1, 1,
   1, 0, 0, 1, null, '1', sysdate(), sysdate());

insert into sai_user_agent (user_id, agent_id, create_dt)
select o.platform_user_id, @utility_agent_id, sysdate()
from sai_openapi_user o
where o.platform_user_id is not null;

-- 自检：model 为空说明库里没有启用的 CHAT 模型
select a.id as agent_id, a.name, a.app_id, a.chat_model_id, m.model_name, a.status
from sai_agent a
         left join sai_model_config m on m.id = a.chat_model_id
where a.id = @utility_agent_id;

select ua.user_id, ua.agent_id, u.username
from sai_user_agent ua
         left join sai_user u on u.id = ua.user_id
where ua.agent_id = @utility_agent_id;

-- ----------------------------
-- 线上只更新提示词（不要整段重跑本文件：上面的 delete 会清空会话与订阅）
-- 文案唯一正本是本文件 insert 里的 instruction，拷到下面执行，避免两处漂移：
-- update sai_agent
-- set instruction = '<上面 insert 的 instruction 原文>',
--     update_dt = sysdate()
-- where id = 9001;
--
-- update sai_agent
-- set instruction = '<上面 9002 insert 的 instruction 原文>',
--     update_dt = sysdate()
-- where id = 9002;
-- ----------------------------