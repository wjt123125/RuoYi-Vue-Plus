-- ----------------------------
-- 数据总线告警规则实测种子（阶段5）
--
-- 本文件只管规则本身；验收夹具各自归位：
--   ① randomFail 随机失败组件：databus_component_seed.sql 第 20 行（Java 脚本件）；
--   ② 链路 alert-test-random-fail：databus_chain_mock_data.sql（id=...902，草稿态，
--      灌库后需在链路工作台点一次「发布」，EL 推 Rule-DB 后才能正式执行落账）；
--      通知链 alert-test-send-mail（id=...901）同理需发布——CHAIN 渠道靠它发信；
--   ③ 重启后端让库存脚本件编译注册（ScriptComponentStartupRegistrar 只在启动时跑）。
--
-- 前置：databus_alert.sql 已执行（规则表/记录表 + 告警菜单 075~080）。
--
-- 验收打法：发布两条链后连续手动执行 alert-test-random-fail，八成失败、两成成功；
--   凑齐 5 分钟窗口内连错 3 次 → 告警管理页点「手动评估」生成 FIRING 并双通道发信：
--   EMAIL 渠道一封 + 通知链 alert-test-send-mail 一封（收件人取规则 notify_config.CHAIN.params.mailTo），
--   且 databus_execution 落一条 trigger_source=ALERT 的通知链总账（CHAIN 渠道记 DISPATCHED=已投递）；
--   再评估不重复发信；继续执行出现成功 → 再评估自动翻 RECOVERED；
--   之后再次连错 3 次发第二轮——整套循环可无限重复。
--
-- 幂等：固定规则 ID，先按 id 删旧（含告警记录，等同复位 FIRING 状态）再插，可重复执行。
-- ID 段：告警规则种子占 1763000000000000001 起（业务数据，与菜单 ID 段无关）。
-- ----------------------------
set names utf8mb4;

delete from databus_alert_record where rule_id = 1763000000000000001;
delete from databus_alert_rule where id = 1763000000000000001;

insert into databus_alert_rule
  (id, rule_name, chain_code, rule_type, window_minutes, threshold_count, threshold_rate,
   notify_type, notify_config, enabled, remark, create_time, update_time)
values
  (1763000000000000001, '告警测试-随机失败·连错3次', 'alert-test-random-fail', 'CONSECUTIVE_FAILURE',
   5, 3, null, 'EMAIL,CHAIN',
   '{"EMAIL":{"emails":"2642778806@qq.com"},"CHAIN":{"chainCode":"alert-test-send-mail","params":{"mailTo":"2642778806@qq.com"}}}',
   'Y',
   '实测种子：窗口5分钟内最近3次执行全部失败即触发；双通道通知——EMAIL 直发 + CHAIN 调 alert-test-send-mail（收件人取 params.mailTo，按环境修改；params 合并进告警事件根部、事件字段优先）；两条链路均需发布后生效，见 databus_chain_mock_data.sql；randomFail 组件见 databus_component_seed.sql',
   sysdate(), sysdate());