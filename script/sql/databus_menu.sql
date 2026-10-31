-- ----------------------------
-- 数据总线菜单与权限正本 databus_menu.sql（2026-10-23 全量重建型）
-- 取代此前散落在 chain/component/market/connection/execution/monitor/alert/workspace
--   八个脚本里的菜单段，以及补丁脚本 databus_admin_menu_cleanup.sql（均已退役删除）。
--
-- 结构：①按三段 id 范围全量清旧（sys_role_menu 连坐 + 悬空兜底 + sys_menu 全删）
--       → ②全量 insert：1 条 M 目录 + 59 条 F 权限行，全部直接挂 M 20000。
-- 幂等：开头即 wipe，重跑等于整体重建；不写增量补丁、不写 not exists 条件插入。
--
-- 【tabs 收敛终态（docs/databus-workspace-tabs.md §13.2）】
--   后台菜单只留 M 目录 20000 一个容器行，全部 F 权限行直接挂靠；
--   工作台页面 /databus/workspace 由前端固定路由直达（plus-ui router/index.ts），
--   /databus 动态枝已被 permission.ts 整枝剔除，不再需要任何 C 行页面菜单。
--   F 行的 perms 是接口鉴权与角色授权树的唯一载体。
--
-- 【5 条 list 载体行（403 修复）】
--   databus:directory:list / connection:list / execution:list / monitor:list / alert:list
--   原长在已删除的 5 条 C 行（20009/010/060/070/075）身上，收敛后全库无载体，
--   非 admin 调列表接口必 403。本正本为各域新增独立 F 行承载：
--   20024 / 1762000000000000016 / 064 / 073 / 081。
--
-- 配套执行：本脚本先行（重建菜单），databus_role.sql 后行（动态子树重授
--   = 20000 + 59 条 F = 60 行；gov 全量、ops 剔除治理专属权限串，清单见 databus_role.sql §2.2）。
--
-- id 段划分（databus 专属，wipe 即按此三段，已与全库菜单清单核验：系统菜单
--   最高 1761400000000001623，与三段无交集，误伤为零）：
--   段 A：1761400000000020000 ~ 1761400000000029999（M 目录 + 编排器/组件/市场/收藏/list 载体）
--   段 B：1762000000000000000 ~ 1762000000000000099（链路/连接/执行/监控/告警）
--   段 C：1762000000000020000 ~ 1762000000000029999（目录四 F 行）
--
-- sys_menu 22 列顺序：menu_id, menu_name, parent_id, order_num, path, component, query_param,
--   is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu, ext,
--   create_dept, create_by, create_time, update_by, update_time, remark
-- 逐行单条 insert：本机 mysql 客户端「多行批量 values + 中文」稳定报 1264 Out of range。
-- ----------------------------

-- ----------------------------
-- ① 全量清旧（三段 id 范围；角色授权连坐清理 + 悬空兜底 + 菜单全删）
-- ----------------------------
delete rm from sys_role_menu rm
join sys_menu m on m.menu_id = rm.menu_id
where (m.menu_id between 1761400000000020000 and 1761400000000029999)
   or (m.menu_id between 1762000000000000000 and 1762000000000000099)
   or (m.menu_id between 1762000000000020000 and 1762000000000029999);

-- 悬空残行兜底：菜单行已不存在、但 sys_role_menu 还指向三段 id 的历史残行（如孤儿 041~44）
delete from sys_role_menu
where (menu_id between 1761400000000020000 and 1761400000000029999)
   or (menu_id between 1762000000000000000 and 1762000000000000099)
   or (menu_id between 1762000000000020000 and 1762000000000029999);

delete from sys_menu
where (menu_id between 1761400000000020000 and 1761400000000029999)
   or (menu_id between 1762000000000000000 and 1762000000000000099)
   or (menu_id between 1762000000000020000 and 1762000000000029999);

-- ----------------------------
-- ② 顶层目录（唯一容器行；工作台页面走前端固定路由，不再建 C 行）
-- ----------------------------
insert into sys_menu values (1761400000000020000, '数据总线', 0, 7, 'databus', null, '', 'N', 'Y', 'M', '0', '0', '', 'tree', '', '', NULL, NULL, sysdate(), NULL, NULL, '数据总线目录');

-- ----------------------------
-- ③ 工作区：目录 + 收藏（order 1~8）
-- ----------------------------
insert into sys_menu values (1761400000000020024, '目录列表查看', 1761400000000020000, 1, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区统一资源树 list 载体（tabs 收敛后由原 C 行 20009 迁 F 行）');
insert into sys_menu values (1762000000000020041, '目录查询', 1761400000000020000, 2, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000020042, '目录新增', 1761400000000020000, 3, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区统一资源树右键新建目录');
insert into sys_menu values (1762000000000020043, '目录修改', 1761400000000020000, 4, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '重命名/排序/拖拽换父（后端防环）/链路与组件归属移动');
insert into sys_menu values (1762000000000020044, '目录删除', 1761400000000020000, 5, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:directory:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '子目录非空/挂链路/挂组件均拦截，不做级联删除');
insert into sys_menu values (1761400000000020015, '收藏查询', 1761400000000020000, 6, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '工作区统一资源树「我的收藏」列表');
insert into sys_menu values (1761400000000020016, '收藏新增', 1761400000000020000, 7, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '叶子一键收藏');
insert into sys_menu values (1761400000000020017, '收藏取消', 1761400000000020000, 8, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:favorite:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '叶子/收藏条目取消收藏');

-- ----------------------------
-- ④ 链路（order 10~17）
-- ----------------------------
insert into sys_menu values (1762000000000000030, '链路列表查看', 1761400000000020000, 10, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '链列表/统计、组件与脚本引擎列表、连接选择等 GET 端点共用（databus:editor:list 载体）');
insert into sys_menu values (1762000000000000021, '链路查询', 1761400000000020000, 11, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000022, '链路新增', 1761400000000020000, 12, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000023, '链路修改', 1761400000000020000, 13, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000024, '链路删除', 1761400000000020000, 14, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000025, '链路发布', 1761400000000020000, 15, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:publish', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '发布链路：status 0→1/2→1 + version+1');
insert into sys_menu values (1762000000000000026, '链路下线', 1761400000000020000, 16, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:offline', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '下线链路：status 1→2');
insert into sys_menu values (1762000000000000027, '链路模板标记', 1761400000000020000, 17, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:template', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '链路设为/取消精选模板（运营动作）；标记需填模板说明，已发布链路须先下线');

-- ----------------------------
-- ⑤ 编排器（order 21~27；与 021~027 同 perms 是历史既有设计，两套 id 各有授权语义，保留）
-- ----------------------------
insert into sys_menu values (1761400000000020002, '编排器查询', 1761400000000020000, 21, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020003, '编排器新增', 1761400000000020000, 22, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020004, '编排器修改', 1761400000000020000, 23, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020005, '编排器删除', 1761400000000020000, 24, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020006, '编排器导出', 1761400000000020000, 25, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:export', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020007, '编排器导入', 1761400000000020000, 26, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:import', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020008, '编排器试运行', 1761400000000020000, 27, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:editor:run', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '画布预览试运行（1A 仅 EL 生成与语法校验）');

-- ----------------------------
-- ⑥ 组件（order 30~36）
-- ----------------------------
insert into sys_menu values (1761400000000020009, '组件列表查看', 1761400000000020000, 30, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '台账页 /list 与 /script/runtime 权限位');
insert into sys_menu values (1761400000000020010, '组件查询', 1761400000000020000, 31, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020011, '组件新增', 1761400000000020000, 32, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020012, '组件修改', 1761400000000020000, 33, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020013, '组件删除', 1761400000000020000, 34, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1761400000000020014, '脚本编辑', 1761400000000020000, 35, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:script:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '受信作者：保存即编译热更，等同服务端代码发布');
insert into sys_menu values (1761400000000020030, '组件启停', 1761400000000020000, 36, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:component:toggle', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'PUT /databus/component/{id}/status（status 0/1）；与「组件修改」拆开——治理专属，操作员有 edit 也停不了物料投放');

-- ----------------------------
-- ⑦ 市场（order 40~45）
-- ----------------------------
insert into sys_menu values (1761400000000020018, '市场查询', 1761400000000020000, 40, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '市场列表/分类/详情/我的已装（浏览组四端点）');
insert into sys_menu values (1761400000000020019, '市场安装', 1761400000000020000, 41, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:install', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '安装＝fork（复制快照进我的目录 + 记血缘）');
insert into sys_menu values (1761400000000020020, '市场卸载', 1761400000000020000, 42, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:uninstall', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '卸载（删我的副本 + 删血缘；不硬拦引用）');
insert into sys_menu values (1761400000000020021, '市场上架', 1761400000000020000, 43, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:publish', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '提交上架（进待审）+ 下架');
insert into sys_menu values (1761400000000020022, '市场审核', 1761400000000020000, 44, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:review', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '审核（通过→上线 / 驳回→驳回态）');
insert into sys_menu values (1761400000000020023, '市场删除', 1761400000000020000, 45, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:market:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '删市场原件（作者主权；与卸载分离）');

-- ----------------------------
-- ⑦+ 连接器（order 46~49；万物皆包 §十五，databus_connector.sql 配套）
-- ----------------------------
insert into sys_menu values (1761400000000020025, '连接器列表查看', 1761400000000020000, 46, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connector:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'GET /databus/connector/defs（内置∪def 合并视图；连接管理页 CONNECTOR_OPTIONS 动态化）');
insert into sys_menu values (1761400000000020026, '连接器包上传', 1761400000000020000, 47, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connector:upload', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'POST /databus/connector/packages（市场 tab 内上传 zip，校验+落盘+入库；同 type 升版本＝热更新）');
insert into sys_menu values (1761400000000020027, '连接器启停与废弃', 1761400000000020000, 48, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connector:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'POST defs/{type}/enable|disable|deprecate（运行层 INACTIVE/ENABLED/DISABLED/DEPRECATED；未启用/禁用挡新建连接不拦存量；废弃＝停机退役的终态，不可逆，之后只能卸载）');
insert into sys_menu values (1761400000000020028, '连接器卸载', 1761400000000020000, 49, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connector:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, 'DELETE defs/{type}（默认拒绝在用连接；?force=true 管理员强制，存量连接悬空自然衰减）；menu_id 20029（2026-10-24 施工纠偏：原 20028 与组件列表查看撞 id，decisions 菜单收敛终态未覆盖此撞号）');

-- ----------------------------
-- ⑧ 连接（order 50~55）
-- ----------------------------
insert into sys_menu values (1762000000000000016, '连接列表查看', 1761400000000020000, 50, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '连接管理列表接口载体（tabs 收敛后由原 C 行 010 迁 F 行）');
insert into sys_menu values (1762000000000000011, '连接查询', 1761400000000020000, 51, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000012, '连接新增', 1761400000000020000, 52, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000013, '连接修改', 1761400000000020000, 53, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000014, '连接删除', 1761400000000020000, 54, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '');
insert into sys_menu values (1762000000000000015, '连接测试', 1761400000000020000, 55, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:connection:test', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '测试连接是否可用');

-- ----------------------------
-- ⑨ 执行（order 60~63）
-- ----------------------------
insert into sys_menu values (1762000000000000064, '执行记录列表查看', 1761400000000020000, 60, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '执行记录列表接口载体（tabs 收敛后由原 C 行 060 迁 F 行）');
insert into sys_menu values (1762000000000000061, '记录查询', 1761400000000020000, 61, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询执行记录详情（总账+节点明细）');
insert into sys_menu values (1762000000000000062, '手动执行/重跑', 1761400000000020000, 62, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:execute', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动执行已发布链路/以历史入参重跑，均产生新记录');
insert into sys_menu values (1762000000000000063, '保留期清理', 1761400000000020000, 63, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:execution:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动物理清理过期执行记录（定时任务每天凌晨按保留期自动执行）');

-- ----------------------------
-- ⑩ 监控（order 70~72）
-- ----------------------------
insert into sys_menu values (1762000000000000073, '指标列表查看', 1761400000000020000, 70, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:monitor:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '监控大盘列表接口载体（tabs 收敛后由原 C 行 070 迁 F 行）');
insert into sys_menu values (1762000000000000071, '指标查询', 1761400000000020000, 71, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:monitor:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询大盘卡片/趋势/排行榜/错误流数据');
insert into sys_menu values (1762000000000000072, '指标聚合', 1761400000000020000, 72, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:monitor:aggregate', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动触发指定小时指标聚合（定时任务每小时自动执行）');

-- ----------------------------
-- ⑪ 告警（order 80~85）
-- ----------------------------
insert into sys_menu values (1762000000000000081, '告警列表查看', 1761400000000020000, 80, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:list', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '告警管理列表接口载体（tabs 收敛后由原 C 行 075 迁 F 行）');
insert into sys_menu values (1762000000000000076, '告警查询', 1761400000000020000, 81, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:query', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '查询告警规则与告警记录');
insert into sys_menu values (1762000000000000077, '告警新增', 1761400000000020000, 82, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:add', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '新增告警规则');
insert into sys_menu values (1762000000000000078, '告警修改', 1761400000000020000, 83, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:edit', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '修改告警规则');
insert into sys_menu values (1762000000000000079, '告警删除', 1761400000000020000, 84, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:remove', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '删除告警规则');
insert into sys_menu values (1762000000000000080, '手动评估', 1761400000000020000, 85, '', '', '', 'N', 'Y', 'F', '0', '0', 'databus:alert:evaluate', '#', '', '', NULL, NULL, sysdate(), NULL, NULL, '手动触发一次告警规则评估（定时任务每分钟自动执行）');
