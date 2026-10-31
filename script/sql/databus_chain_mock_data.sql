-- ----------------------------
-- 数据总线示例链路种子数据（开发期手工维护：mock-presets.ts 与生成脚本已删除；零 ALTER 约束，整文件可直接重复执行）
-- 执行顺序：先 databus_chain.sql（含 drop table 与建表/菜单）再跑本文件。带 is_template=1 的 10 条为精选模板库首批模板。
-- 全部为草稿状态（status=0）；发布走前端/接口 publish，后端实时生成 EL 推 Rule-DB。
-- id 段 1762000000000100001 起。
-- 本文件可重复执行：开头先按全部种子 id 清场再逐条插入（早期残留 code alert-test-always-fail 一并清掉）。
-- 只清 databus_chain 草稿本体；已发布链路留在 lf_chain 的旧规则不动，重新发布会覆盖。
-- ----------------------------
-- 归属：种子资源统一挂到测试账号 test 名下（create_by/create_dept 直接写入 INSERT，不使用事后 UPDATE）。
-- 例外：chain-ref / subChain_demo 两条级联测试料挂 admin（2026-10-24 施工：级联安装测试需「admin 卖家→test1 审核→test 买家」三方闭环，见 decisions §十四-2）。
set @db_seed_test_uid := (select user_id from sys_user where user_name = 'test' and del_flag = '0' limit 1);
set @db_seed_test_did := (select dept_id from sys_user where user_name = 'test' and del_flag = '0' limit 1);
set @db_seed_admin_uid := (select user_id from sys_user where user_name = 'admin' and del_flag = '0' limit 1);
set @db_seed_admin_did := (select dept_id from sys_user where user_name = 'admin' and del_flag = '0' limit 1);

delete from databus_chain
where chain_code = 'alert-test-always-fail'
   or id in
( 1762000000000100001, 1762000000000100002, 1762000000000100003, 1762000000000100004, 1762000000000100005,
  1762000000000100006, 1762000000000100007, 1762000000000100008, 1762000000000100009, 1762000000000100010,
  1762000000000100011, 1762000000000100012, 1762000000000100013, 1762000000000100014, 1762000000000100015,
  1762000000000100016, 1762000000000100017, 1762000000000100018, 1762000000000100019, 1762000000000100020,
  1762000000000100021, 1762000000000100022, 1762000000000100023, 1762000000000100024, 1762000000000100025,
  1762000000000100026, 1762000000000100027, 1762000000000100028, 1762000000000100029,
  1762000000000100030, 1762000000000100031, 1762000000000100032, 1762000000000100033,
  1762000000000100034, 1762000000000100035, 1762000000000100036, 1762000000000100037,
  1762000000000100901, 1762000000000100902 );

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100001, 'script-if-booleanScript', '条件脚本与脚本（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"IF","condition":{"id":"booleanScript1","type":"NodeBooleanComponent","properties":{"tag":"booleanScript1","data":"{\\"language\\":\\"groovy\\",\\"script\\":\\"def v = databusContext.read(''$.flag''); return v == true\\"}"}},"children":[{"type":"THEN","children":[{"id":"script1","type":"NodeComponent","properties":{"tag":"script1","data":"{\\"language\\":\\"groovy\\",\\"script\\":\\"def name = databusContext.read(''$.name''); databusContext.save(''$.script1.greeting'', ''hello '' + name)\\"}"}}]},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.response1.msg\\",\\"value\\":\\"已完成\\"}"}}]}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":true,"required":false},{"path":"$.name","type":"text","defaultValue":"databus","required":false}]', sysdate(), 'Groovy 脚本读写数据空间与 IF 条件脚本（结构展示）', '1', 'Groovy 脚本样板：脚本节点读写数据空间、条件脚本控制 IF 分支，适合一行表达式放不下的逻辑。', 90, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100002, 'http-anonymous-chain', 'HTTP 免授权双请求（本地 RuoYi）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/auth/code\\",\\"mappings\\":[{\\"field\\":\\"captchaEnabled\\",\\"path\\":\\"{{ $.data.captchaEnabled }}\\"},{\\"field\\":\\"uuid\\",\\"path\\":\\"{{ $.data.uuid }}\\",\\"required\\":false}]}"}},{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest2","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/\\",\\"headers\\":{\\"X-Demo\\":\\"databus\\"}}"}}]}', 'BASIC', sysdate(), '免授权免加密：JSON 抽取 + 纯文本响应', '1', '免登录、无外部依赖：连续两个本地 HTTP 请求，学习 JSON 字段抽取与响应映射的最小示例，导入即可试运行。', 10, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100003, 'bpm-flow', 'BPM 全链路（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"processStart","type":"NodeComponent","properties":{"tag":"processStart1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"processDefId\\":\\"obj_61a4e68a43e043fa87f89530a503f9ae\\",\\"uid\\":\\"admin\\",\\"title\\":\\"申请-{{ $.request.code }}\\"}"}},{"id":"boCreate","type":"NodeComponent","properties":{"tag":"boCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"method\\":\\"create\\",\\"bindId\\":\\"{{ $.processStart1.processInstanceId }}\\",\\"uid\\":\\"admin\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"{{ $.request.boList }}\\",\\"rewrite\\":{\\"strategy\\":\\"all\\",\\"path\\":\\"$.response.boList\\"}}]}"}},{"id":"fileUpload","type":"NodeComponent","properties":{"tag":"fileUpload1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"sourcePath\\":\\"{{ $.request.files }}\\",\\"boId\\":\\"{{ $.boCreate1.boResults[0].records[0].ID }}\\",\\"appId\\":\\"com.awspaas.user.apps.data.bus\\",\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"boItemName\\":\\"BO_FIELD_FILE\\",\\"processInstId\\":\\"{{ $.processStart1.processInstanceId }}\\"}"}},{"id":"fileDownload","type":"NodeComponent","properties":{"tag":"fileDownload1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"boId\\":\\"{{ $.boCreate1.boResults[0].records[0].ID }}\\",\\"fieldName\\":\\"BO_FIELD_FILE\\"}"}},{"id":"taskComplete","type":"NodeComponent","properties":{"tag":"taskComplete1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"processInstanceId\\":\\"{{ $.processStart1.processInstanceId }}\\",\\"uid\\":\\"admin\\",\\"failOnError\\":false}"}}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true},{"path":"$.request.code","type":"text","defaultValue":"D001","required":true},{"path":"$.request.boList","type":"array","required":true,"defaultValue":[{"BO_FIELD_TEXT":"测试","BO_FIELD_USER":"张三","BO_FIELD_NUM":123456}]},{"path":"$.request.files","type":"array","required":true,"defaultValue":[{"fileName":"hello.txt","fileContent":"aGVsbG8gZGF0YWJ1cw==","securityLevel":1}]}]', sysdate(), '会话→启流程→建 BO→附件上传/读回→完任务', '1', '集成类样板（需 BPM 环境与 bpm-default 连接器）：会话→启流程→建 BO→附件上传/读回→完任务，BPM 全链路参考。', 100, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100004, 'bpm-bo-update', 'BPM BO 查改验证（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":10,\\"conditionSourcePath\\":\\"{{ $.request.conditions }}\\"}}"}},{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"hasData1","data":"{\\"path\\":\\"{{ $.boQuery1.records[0] }}\\",\\"op\\":\\"notBlank\\"}"}},"children":[{"type":"THEN","children":[{"id":"dataPatch","type":"NodeComponent","properties":{"tag":"dataPatch1","data":"{\\"target\\":\\"$.boQuery1.records[*]\\",\\"patch\\":{\\"BO_FIELD_USER\\":\\"{{ $.request.newUser }}\\"}}"}},{"id":"boUpdate","type":"NodeComponent","properties":{"tag":"boUpdate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"{{ $.boQuery1.records }}\\"}]}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery2","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":10,\\"conditionSourcePath\\":\\"{{ $.request.conditions }}\\"}}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"查询无数据，已跳过更新\\"}"}}]}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true},{"path":"$.request.conditions","type":"array","required":true,"defaultValue":[{"fieldName":"BO_FIELD_TEXT","operator":"=","paramValue":"测试"}]},{"path":"$.request.newUser","type":"text","required":true,"defaultValue":"张三-已更新"}]', sysdate(), '按查询结果分流：有数据则改并验证，无则跳过', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100005, 'bpm-bo-delete', 'BPM BO 条件清理（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":50,\\"conditionSourcePath\\":\\"{{ $.request.conditions }}\\"}}"}},{"id":"boDelete","type":"NodeComponent","properties":{"tag":"boDelete1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"method\\":\\"remove\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"{{ $.boQuery1.records }}\\"}]}"}}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true},{"path":"$.request.conditions","type":"array","required":true,"defaultValue":[{"fieldName":"BO_FIELD_TEXT","operator":"=","paramValue":"测试"}]}]', sysdate(), '按条件查出记录并按 ID 批量删除', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100006, 'bpm-rds-methods', 'BPM SQL 八方法全覆盖（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"drop table if exists DATABUS_RDS_TEST\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute2","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"create table DATABUS_RDS_TEST (ID varchar(32) not null primary key, NAME varchar(64), AGE int, SALARY decimal(10,2))\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute3","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"batch\\",\\"sql\\":\\"insert into DATABUS_RDS_TEST (ID, NAME, AGE, SALARY) values (?, ?, ?, ?)\\",\\"args\\":[[\\"t1\\",\\"张三\\",30,8800.5],[\\"t2\\",\\"李四\\",25,7600],[\\"t3\\",\\"王五\\",41,12000.75]]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute4","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getString\\",\\"sql\\":\\"select NAME from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute5","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getInt\\",\\"sql\\":\\"select AGE from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t2\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute6","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getLong\\",\\"sql\\":\\"select count(*) from DATABUS_RDS_TEST\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute7","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getDouble\\",\\"sql\\":\\"select SALARY from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t3\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute8","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMap\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute9","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMaps\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST order by ID\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute10","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"update DATABUS_RDS_TEST set NAME = ? where ID = ?\\",\\"args\\":[\\"张三-改\\",\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute11","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"batch\\",\\"sql\\":[\\"update DATABUS_RDS_TEST set AGE = AGE + 1 where ID = ''t2''\\",\\"delete from DATABUS_RDS_TEST where ID = ''t3''\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute12","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMaps\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST order by ID\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute13","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"drop table DATABUS_RDS_TEST\\"}"}}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true}]', sysdate(), '临时表自清理，覆盖查询写入与批量两种模式', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100007, 'bpm-idcard-to-userid', 'BPM 身份证换用户（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"idCardToUserId","type":"NodeComponent","properties":{"tag":"idCardToUserId1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"fields\\":[{\\"path\\":\\"{{ $.request.idCards }}\\",\\"separator\\":\\",\\"},{\\"path\\":\\"{{ $.request.idCardsPartial }}\\",\\"separator\\":\\",\\"}]}"}}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true},{"path":"$.request.idCards","type":"text","required":true,"defaultValue":"522121199701255412"},{"path":"$.request.idCardsPartial","type":"text","required":true,"defaultValue":"522121199701255412,34010119900202002X"}]', sysdate(), '逗号分隔身份证号批量换 userId 原地写回', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100008, 'bpm-idcard-all-miss', 'BPM 身份证全未命中（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"{{ $.request.password }}\\"}"}},{"id":"idCardToUserId","type":"NodeComponent","properties":{"tag":"idCardToUserId1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"fields\\":[{\\"path\\":\\"{{ $.request.idCards }}\\",\\"separator\\":\\",\\"}]}"}}]}', 'BASIC', '[{"path":"$.request.password","type":"text","defaultValue":"changeme","required":true},{"path":"$.request.idCards","type":"text","required":true,"defaultValue":"34010119900202002X,999999999999999999"}]', sysdate(), '全部身份证查无用户，节点预期抛错中断', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100009, 'local-flag', '入参开关（纯本地，无外部依赖）', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.flag }}\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"flag 为真\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"flag 为假\\"}"}}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":true,"required":false}]', sysdate(), '按入参 flag 真假写不同赋值', '1', '入参开关驱动链路：通过链路入参登记表传入开关值控制行为，适合先理解「入参登记 → 试运行预填 → 必填校验」。', 20, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100010, 'serial-all', '串行 THEN', 1, '0', null, null, '{"type":"THEN","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/auth/code\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.note\\",\\"value\\":\\"串行演示\\"}"}},{"id":"fieldMap","type":"NodeComponent","properties":{"tag":"fieldMap1","data":"{\\"mappings\\":[{\\"from\\":\\"{{ $.httpRequest1.response.code }}\\",\\"to\\":\\"$.fieldMap1.code\\"}]}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"msg\\":\\"{{ $.httpRequest1.response.msg }}\\",\\"dataPath\\":\\"{{ $.fieldMap1 }}\\"}"}}]}', 'BASIC', sysdate(), '请求→赋值→映射→响应四类真组件串联', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100011, 'then-empty', '空 THEN 占位', 1, '0', null, null, '{"type":"THEN","children":[]}', 'BASIC', sysdate(), '空 children 验证占位节点与 start 连边（结构展示）', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100012, 'when-parallel', '并行 WHEN', 1, '0', null, null, '{"type":"WHEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"并行 A\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"并行 B\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue3","data":"{\\"path\\":\\"$.setValue3.out\\",\\"value\\":\\"并行 C\\"}"}}]}', 'BASIC', sysdate(), '三路赋值并行，验证扇出扇入与 junction 多入边', '1', '并行执行样板：多路任务同时运行、结果各自写回数据空间，理解 WHEN 与并发数据隔离。', 40, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100013, 'if-branch', '条件 IF 双分支', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.code }}\\",\\"op\\":\\"eq\\",\\"value\\":200}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.result\\",\\"value\\":\\"code 命中\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.result\\",\\"value\\":\\"code 未命中\\"}"}}]}', 'BASIC', '[{"path":"$.code","type":"number","defaultValue":200,"required":false}]', sysdate(), '按入参 code 是否等于 200 走不同赋值', '1', '条件分支样板：按数据内容判断后走不同处理路径，IF/THEN/ELSE 基本范式。', 30, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100014, 'if-empty-false', 'IF 空假分支', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.flag }}\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"真分支执行\\"}"}}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":true,"required":false}]', sysdate(), '只有真分支，假分支为占位节点', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100015, 'switch-cases', 'SWITCH 按值选分支（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"SWITCH","properties":{"outletLabels":["caseA","caseB"]},"condition":{"id":"switchRoute","type":"NodeSwitchComponent","properties":{"tag":"switchRoute1","data":"{\\"source\\":\\"{{ $.code }}\\",\\"cases\\":[{\\"value\\":\\"A\\",\\"target\\":\\"caseA\\"},{\\"value\\":\\"B\\",\\"target\\":\\"caseB\\"}]}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"命中 A 分支\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"命中 B 分支\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"msg\\":\\"路由完成\\"}"}}]}', 'BASIC', '[{"path":"$.code","type":"text","defaultValue":"A","required":false}]', sysdate(), '按 code 值命中对应 case 分支', '1', '按值路由样板：根据字段值命中多个分支之一（SWITCH），未命中报错与候选清单行为可试。', 70, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100016, 'for-count', 'FOR 计数循环（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"FOR","condition":{"id":"forLoop","type":"NodeForComponent","properties":{"tag":"forLoop1","data":"{\\"count\\":3}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.forLoop1.cursor\\",\\"value\\":\\"$i\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"{{ $.forLoop1 }}\\"}"}}]}', 'BASIC', sysdate(), '循环 3 轮，$i 下标写入数据空间', '1', '计数循环样板：固定次数重复处理，学习 FOR 配置与循环索引 $i 的用法。', 50, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100017, 'while-loop', 'WHILE 循环（零次执行安全示例）', 1, '0', null, null, '{"type":"WHILE","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.flag }}\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"循环体执行\\"}"}}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":false,"required":false}]', sysdate(), '条件为假时循环体零次执行', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100018, 'iterator-items', 'ITERATOR 迭代数组（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop1","data":"{\\"source\\":\\"{{ $.items }}\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.iteratorLoop1.lastName\\",\\"value\\":\\"{{ $.items[$i].name }}\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"{{ $.iteratorLoop1 }}\\"}"}}]}', 'BASIC', '[{"path":"$.items","type":"array","defaultValue":[{"name":"甲"},{"name":"乙"},{"name":"丙"}],"required":false}]', sysdate(), '迭代数组，$i 取每项名称', '1', '数组迭代样板：对入参数组逐条处理，业务批处理最常用范式，含单层/嵌套扩展提示。', 60, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100019, 'iterator-nested', 'ITERATOR 双层嵌套（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop1","data":"{\\"source\\":\\"{{ $.groups }}\\"}"}},"children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop2","data":"{\\"source\\":\\"{{ $.groups[$i].users }}\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.iteratorLoop1.last\\",\\"value\\":\\"{{ $.groups[$i].users[$j].name }}\\"}"}}]}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"{{ $.iteratorLoop1 }}\\"}"}}]}', 'BASIC', '[{"path":"$.groups","type":"array","defaultValue":[{"users":[{"name":"甲一"},{"name":"甲二"}]},{"users":[{"name":"乙一"}]}],"required":false}]', sysdate(), '双层迭代：外层 $i 内层 $j', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, is_template, template_desc, template_sort, create_dept, create_by)
values
  (1762000000000100020, 'catch-flow', 'CATCH 异常捕获（真触发异常分支）', 1, '0', null, null, '{"type":"CATCH","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://127.0.0.1:9999/no-such-service\\"}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"下游不可用，已兜底\\"}"}}]}', 'BASIC', sysdate(), '请求不存在端口触发异常，响应组件兜底', '1', '异常捕获样板：节点失败时转入异常分支兜底，保证链路不中断的容错范式（真实触发异常路径）。', 80, null, null);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100021, 'catch-empty', 'CATCH 空异常槽', 1, '0', null, null, '{"type":"CATCH","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"正常执行\\"}"}}]}', 'BASIC', sysdate(), '仅正常主体，异常槽为占位节点', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100022, 'and-logic', 'AND 与逻辑', 1, '0', null, null, '{"type":"AND","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.a }}\\",\\"op\\":\\"isTrue\\"}"}},{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition2","data":"{\\"path\\":\\"{{ $.b }}\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', '[{"path":"$.a","type":"boolean","defaultValue":true,"required":false},{"path":"$.b","type":"boolean","defaultValue":true,"required":false}]', sysdate(), '两个布尔条件取与（结构展示）', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100023, 'or-logic', 'OR 或逻辑', 1, '0', null, null, '{"type":"OR","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.a }}\\",\\"op\\":\\"isTrue\\"}"}},{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition2","data":"{\\"path\\":\\"{{ $.b }}\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', '[{"path":"$.a","type":"boolean","defaultValue":false,"required":false},{"path":"$.b","type":"boolean","defaultValue":true,"required":false}]', sysdate(), '两个布尔条件取或（结构展示）', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100024, 'not-logic', 'NOT 非逻辑', 1, '0', null, null, '{"type":"NOT","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.flag }}\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":false,"required":false}]', sysdate(), '对布尔条件取反（结构展示）', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100025, 'chain-ref', 'CHAIN 子流程引用', 1, '0', null, null, '{"type":"THEN","children":[{"id":"subChain_demo","type":"CHAIN"}]}', 'BASIC', sysdate(), '引用子链 subChain_demo（结构展示，生成 EL：THEN(subChain_demo);）', @db_seed_admin_did, @db_seed_admin_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100026, 'nested-complex', '嵌套综合', 1, '0', null, null, '{"type":"THEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue0","data":"{\\"path\\":\\"$.setValue0.out\\",\\"value\\":\\"串行头\\"}"}},{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"{{ $.flag }}\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"IF 真\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"IF 假\\"}"}}]},{"type":"WHEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue3","data":"{\\"path\\":\\"$.setValue3.out\\",\\"value\\":\\"并行 A\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue4","data":"{\\"path\\":\\"$.setValue4.out\\",\\"value\\":\\"并行 B\\"}"}}]}]}', 'BASIC', '[{"path":"$.flag","type":"boolean","defaultValue":true,"required":false}]', sysdate(), 'THEN(赋值, IF(flag 双分支), WHEN(并行两路))', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100027, 'nested-catch-in-then', 'THEN 内嵌 CATCH（正常路径）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"串行头\\"}"}},{"type":"CATCH","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"try 主体\\"}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"进入异常处理\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response2","data":"{\\"result\\":true,\\"msg\\":\\"主流程完成\\"}"}}]}', 'BASIC', sysdate(), 'THEN 内嵌 CATCH，异常槽不触发', @db_seed_test_did, @db_seed_test_uid);

-- chain-ref 引用的子链本体（非模板普通链，供 CHAIN 节点下拉选择；纯本地无入参；属主 admin 见头部例外注释）
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100028, 'subChain_demo', '子链示例（被 chain-ref 引用）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.sub.out\\",\\"value\\":\\"子链执行\\"}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"msg\\":\\"subChain_demo 已执行\\"}"}}]}', 'BASIC', sysdate(), '被 chain-ref 的 CHAIN 节点引用，验证子流程下拉与运行时 chainMap 解析', @db_seed_admin_did, @db_seed_admin_uid);
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100029, 'catch-type-route', 'CATCH 按异常类型路由（纯本地·组件版）', 1, '0', null, null, '{\"type\":\"CATCH\",\"children\":[{\"id\":\"exceptionThrower\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"exceptionThrower1\",\"data\":\"{\\"exceptionClass\\":\\"{{ $.exceptionClass }}\\",\\"message\\":\\"模拟异常：{{ $.exceptionClass }}\\"}\"}},{\"type\":\"THEN\",\"children\":[{\"id\":\"exceptionInspect\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"exceptionInspect1\",\"data\":\"{\\"routes\\":[{\\"type\\":\\"ARGUMENT\\",\\"className\\":\\"java.lang.IllegalArgumentException\\"},{\\"type\\":\\"NPE\\",\\"className\\":\\"java.lang.NullPointerException\\"},{\\"type\\":\\"STATE\\",\\"className\\":\\"java.lang.IllegalStateException\\"}],\\"matchCause\\":true,\\"defaultType\\":\\"OTHER\\"}\"}},{\"type\":\"SWITCH\",\"properties\":{\"outletLabels\":[\"argHandler\",\"npeHandler\",\"stateHandler\",\"otherHandler\"]},\"condition\":{\"id\":\"switchRoute\",\"type\":\"NodeSwitchComponent\",\"properties\":{\"tag\":\"exSwitch1\",\"data\":\"{\\"source\\":\\"{{ $.exceptionInspect1.type }}\\",\\"cases\\":[{\\"value\\":\\"ARGUMENT\\",\\"target\\":\\"argHandler\\"},{\\"value\\":\\"NPE\\",\\"target\\":\\"npeHandler\\"},{\\"value\\":\\"STATE\\",\\"target\\":\\"stateHandler\\"},{\\"value\\":\\"OTHER\\",\\"target\\":\\"otherHandler\\"}]}\"}},\"children\":[{\"id\":\"setValue\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"setValue1\",\"data\":\"{\\"path\\":\\"$.exHandler.result\\",\\"value\\":\\"参数异常已兜底：{{ $.exceptionInspect1.message }}\\"}\"}},{\"id\":\"setValue\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"setValue2\",\"data\":\"{\\"path\\":\\"$.exHandler.result\\",\\"value\\":\\"空指针已兜底：{{ $.exceptionInspect1.message }}\\"}\"}},{\"id\":\"setValue\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"setValue3\",\"data\":\"{\\"path\\":\\"$.exHandler.result\\",\\"value\\":\\"状态冲突已兜底：{{ $.exceptionInspect1.message }}\\"}\"}},{\"id\":\"setValue\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"setValue4\",\"data\":\"{\\"path\\":\\"$.exHandler.result\\",\\"value\\":\\"其他异常已兜底：{{ $.exceptionInspect1.className }}\\"}\"}}]},{\"id\":\"response\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"response1\",\"data\":\"{\\"result\\":false,\\"msg\\":\\"异常已按类型路由：{{ $.exceptionInspect1.type }}\\",\\"dataPath\\":\\"{{ $.exHandler }}\\"}\"}}]}]}', 'BASIC', '[{\"path\":\"$.exceptionClass\",\"type\":\"text\",\"defaultValue\":\"java.lang.IllegalArgumentException\",\"required\":false}]', sysdate(), '零脚本 CATCH 分流示范：入参 exceptionClass 传异常全限定类名，exceptionThrower 抛异常，DO 内 exceptionInspect 按有序 instanceof 识别（支持子类/cause 链），SWITCH 四路兜底。测法：java.lang.IllegalArgumentException→ARGUMENT，java.lang.NullPointerException→NPE，java.lang.IllegalStateException→STATE，java.lang.RuntimeException→OTHER', @db_seed_test_did, @db_seed_test_uid);

-- ----------------------------
-- 告警/邮件通道自测链路（2026-10-12 增，阶段5 监控体系落地·验收②）
-- 两条都是草稿（status=0）：需在链路工作台点发布、推 Rule-DB 后才可正式执行。
-- 告警引擎只认 databus_execution 记录，而试运行不落库（DatabusExecutor.executeByEl 不挂牌），
-- 故验 B 必须走正式执行。
-- ----------------------------
-- A：链路上发信。使用 Java 脚本原子组件 sendMail（databus_component 一行，
-- script_lang=java、script_body 为完整类源码，见 databus_component_seed.sql 的 sendMail 行），
-- 节点 id 引用组件编码 sendMail；收件人取入参 $.mailTo（缺省 wjt123125@qq.com）。
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100901, 'alert-test-send-mail', '告警测试·发信（sendMail 组件）', 1, '0', null, null, '{\"type\":\"THEN\",\"children\":[{\"id\":\"sendMail\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"sendMail1\",\"data\":\"{\\"mailTo\\":\\"{{ $.mailTo }}\\",\\"mailSubject\\":\\"【数据总线自测】告警邮件通道\\",\\"mailText\\":\\"本邮件由链路 alert-test-send-mail 发出，收到即证明数据总线侧邮件通道可用。\\"}\"}}]}', 'BASIC', '[{\"path\":\"$.mailTo\",\"type\":\"text\",\"defaultValue\":\"wjt123125@qq.com\",\"required\":false}]', sysdate(), '邮件通道自测：发布后执行（或画布试运行）本链路，sendMail 组件调平台邮件通道发信，收件人取入参 $.mailTo，缺省 wjt123125@qq.com。组件源码随 databus_component_seed.sql 灌库。', @db_seed_test_did, @db_seed_test_uid);

-- B：随机失败。randomFail 组件（源码见 databus_component_seed.sql 第 20 行）每次执行按 0.8 概率抛异常，
-- 大概率失败、偶尔成功：配「连错 3 次」告警规则后很容易凑齐触发序列，偶尔的成功又能验证自动恢复。
-- 本行由旧版 alert-test-always-fail 改名而来，旧 code 的清场已在文件头部统一处理。
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100902, 'alert-test-random-fail', '告警测试·随机失败（randomFail）', 1, '0', null, null, '{\"type\":\"THEN\",\"children\":[{\"id\":\"randomFail\",\"type\":\"NodeComponent\",\"properties\":{\"tag\":\"randomFail1\",\"data\":\"{\\\"failRate\\\":0.8}\"}}]}', 'BASIC', null, sysdate(), '告警引擎自测：发布后连续手动执行，八成随机失败、两成成功；连错 3 次触发 FIRING 告警，偶发成功后自动恢复，可无限循环复测（规则见 databus_alert_seed.sql）。', @db_seed_test_did, @db_seed_test_uid);

-- ----------------------------
-- 闭包联审测试链组（2026-10-24 闭包联审）：主链 review-main-demo --chain-ref--> 子链 review-sub-demo
-- --NodeComponent--> 组件 reviewEcho（组件行由 databus_component_seed.sql ③ 灌入，需先跑该文件）。
-- 三者都不随市场种子上架，验证「上架主链连带依赖补 PENDING 单 → 审核详情页就地逐个通过 → 主链通过」。
-- 归属 admin（同 chain-ref/subChain_demo 三方闭环口径：admin 上架 → test1 审核）。
-- 前置：databus_component_seed.sql 已执行（reviewEcho 组件行存在，否则终审 gate 报「依赖组件已不存在」）。
-- ----------------------------
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100030, 'review-sub-demo', '联审测试·子链', 1, '0', null, null, '{"type":"THEN","children":[{"id":"reviewEcho","type":"NodeComponent","properties":{"tag":"reviewEcho1","data":"{\\"path\\":\\"$.reviewEcho1.out\\",\\"value\\":\\"联审子链执行\\"}"}}]}', 'BASIC', sysdate(), '被 review-main-demo 的 CHAIN 节点引用，验证连带上架与就地联审', @db_seed_admin_did, @db_seed_admin_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100031, 'review-main-demo', '联审测试·主链', 1, '0', null, null, '{"type":"THEN","children":[{"id":"review-sub-demo","type":"CHAIN"}]}', 'BASIC', sysdate(), 'chain-ref 引用 review-sub-demo：上架本链应连带子链与 reviewEcho 组件各补一张 PENDING 审核单', @db_seed_admin_did, @db_seed_admin_uid);

-- ----------------------------
-- JDBC 连接器实测链组（正本 docs/databus-jdbc-connector-plan.md §十一 阶段 B 验证清单第 2-4 条）
-- 六条链覆盖 dbQuery / dbExecute / dbBatch 三个脚本组件，全部指向连接 jdbc-mysql-demo。
-- 前置三件（缺一即执行报错，报错内容本身就是诊断线索）：
--   1) script/sql/databus_jdbc_demo_mysql.sql 已执行（建库 + databus_demo 账号 + 三张靶表）；
--   2) databus_connection.sql 的 1762000000000020003 行已灌入（jdbc-mysql-demo，enabled=Y）；
--   3) jdbc-mysql 连接器已装载：dev 走 application-dev.yml 的 dev-plugin-dir（需先
--      mvn -pl databus-connectors/databus-connector-jdbc-mysql -am package -DskipTests
--      把驱动放进 target/lib），生产走物料市场上传 connector-package.zip 并启用。
-- 六条都是草稿（status=0），发布走前端/接口 publish。归属 test（同大多数种子链）。
-- 验收看执行记录的数据空间，不用另配 setValue：
--   dbQuery   → $.<tag>.rows / .row / .value / .rowCount / .columns
--   dbExecute → $.<tag>.affectedRows / .generatedKeys
--   dbBatch   → $.<tag>.affectedRows（数组，长度==参数组数）/ .batchCount
-- ----------------------------
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100032, 'jdbc-query-rows', 'JDBC·多行查询（? 占位 + 动态参数）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbQuery","type":"NodeComponent","properties":{"tag":"db1","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"select user_id, user_name, dept_id, salary, status from jdbc_org_user where dept_id = ? and status = ?\\",\\"args\\":[\\"{{ $.request.deptId }}\\",\\"{{ $.request.status }}\\"],\\"resultMode\\":\\"rows\\",\\"maxRows\\":100}"}}]}', 'BASIC', '[{"path":"$.request.deptId","type":"number","defaultValue":103,"required":true},{"path":"$.request.status","type":"text","defaultValue":"0","required":false}]', sysdate(), 'JDBC 最小闭环：验插件装载→建池→参数绑定→结果映射。期望 $.db1.rowCount=2（103 部门两人），rows 里 salary 是字符串（decimalAsString 默认 true）、user_name 中文不乱码。改 deptId=999 应得 rowCount=0 且 rows 为空数组。', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100033, 'jdbc-query-modes', 'JDBC·首行与标量（resultMode 三档）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbQuery","type":"NodeComponent","properties":{"tag":"db2","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"select user_id, user_name, salary from jdbc_org_user where dept_id = ? order by salary desc\\",\\"args\\":[\\"{{ $.request.deptId }}\\"],\\"resultMode\\":\\"first\\"}"}},{"id":"dbQuery","type":"NodeComponent","properties":{"tag":"db3","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"select count(*) from jdbc_org_user where dept_id = ?\\",\\"args\\":[\\"{{ $.request.deptId }}\\"],\\"resultMode\\":\\"scalar\\",\\"scalarType\\":\\"int\\"}"}}]}', 'BASIC', '[{"path":"$.request.deptId","type":"number","defaultValue":105,"required":true}]', sysdate(), '验 resultMode 另两档：$.db2.row 是单个对象（首行，salary 最高的 u003），$.db2.rows 同时也有；$.db3.value 是数字 2 而不是字符串（scalarType=int 生效）。同一连接两个节点应复用同一个池（连接池 key = connectionId + config 指纹）。', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark, create_dept, create_by)
values
  (1762000000000100034, 'jdbc-type-matrix', 'JDBC·类型矩阵全量比对', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbQuery","type":"NodeComponent","properties":{"tag":"dbm","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"select * from jdbc_type_matrix order by id\\",\\"resultMode\\":\\"rows\\"}"}}]}', 'BASIC', sysdate(), 'JdbcResultMapper §7.1 逐列比对料。四行各有看点：full 行验各类型转换与时间格式（c_datetime 必须是 2026-10-31 13:45:09，不带 T/Z）、c_binary/c_varbinary/c_blob 是 Base64、c_json 走 default 兜底成字符串；snowflake 行验 c_bigint=1762000000000100001 转成 String（超 2^53 不丢精度）；all-null 行验所有列都是 null 而非空串或 0；empty-string 行验空串与 NULL 不被混同。', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100035, 'jdbc-execute-keys', 'JDBC·写入并回填自增主键', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbExecute","type":"NodeComponent","properties":{"tag":"dbw","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"insert into jdbc_write_log (user_id, action, payload) values (?, ?, ?)\\",\\"args\\":[\\"{{ $.request.userId }}\\",\\"{{ $.request.action }}\\",\\"{{ $.request.payload }}\\"],\\"returnGeneratedKeys\\":true}"}}]}', 'BASIC', '[{"path":"$.request.userId","type":"text","defaultValue":"u001","required":true},{"path":"$.request.action","type":"text","defaultValue":"manual-test","required":true},{"path":"$.request.payload","type":"text","defaultValue":"由链路写入","required":false}]', sysdate(), '验 dbExecute：$.dbw.affectedRows=1，$.dbw.generatedKeys 是长度 1 的数组（新自增 id）。事务只包本组件内——执行完即提交，可去靶库 select * from jdbc_write_log 复核；重复执行会一直追加行。执行日志里应有 stmtType=INSERT（语句类型审计，不记 SQL 原文）。', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100036, 'jdbc-batch-write', 'JDBC·批量写入（单语句 + 参数组）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbBatch","type":"NodeComponent","properties":{"tag":"dbb","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"insert into jdbc_write_log (user_id, action, payload) values (?, ?, ?)\\",\\"argsList\\":[[\\"{{ $.request.userId }}\\",\\"batch-1\\",\\"第一组：动态参数\\"],[\\"u002\\",\\"batch-2\\",\\"第二组：字面量\\"],[\\"u003\\",\\"batch-3\\",\\"第三组：字面量\\"]],\\"batchSize\\":2}"}}]}', 'BASIC', '[{"path":"$.request.userId","type":"text","defaultValue":"u001","required":true}]', sysdate(), '验 dbBatch 单语句模式：三组参数、batchSize=2 → $.dbb.batchCount=2，$.dbb.affectedRows 长度必须是 3（每组一个值，不是每批一个）。整体一个事务：把第二组的表名改成不存在的列可验全批回滚（三组都不落库）。', @db_seed_test_did, @db_seed_test_uid);

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, input_params, create_time, remark, create_dept, create_by)
values
  (1762000000000100037, 'jdbc-full-loop', 'JDBC·查完再写（节点产出跨节点引用）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"dbQuery","type":"NodeComponent","properties":{"tag":"dbq","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"select user_id, user_name from jdbc_org_user where dept_id = ? order by user_id\\",\\"args\\":[\\"{{ $.request.deptId }}\\"],\\"resultMode\\":\\"rows\\"}"}},{"id":"dbExecute","type":"NodeComponent","properties":{"tag":"dblog","data":"{\\"connectionId\\":\\"jdbc-mysql-demo\\",\\"sql\\":\\"insert into jdbc_write_log (user_id, action, payload) values (?, ?, ?)\\",\\"args\\":[\\"{{ $.dbq.rows[0].user_id }}\\",\\"sync\\",\\"部门 {{ $.request.deptId }} 共 {{ $.dbq.rowCount }} 人，首人 {{ $.dbq.rows[0].user_name }}\\"],\\"returnGeneratedKeys\\":true}"}}]}', 'BASIC', '[{"path":"$.request.deptId","type":"number","defaultValue":108,"required":true}]', sysdate(), '验数据空间跨节点传递：第二个节点的 args 用 JSONPath 取第一个节点的产出（$.dbq.rows[0].user_id / $.dbq.rowCount）。期望 $.dblog.affectedRows=1，靶库里新行的 user_id=u005、payload 里嵌了 rowCount=2 与「钱七」。这条也顺带验中文在参数绑定路径上不乱码（不只是结果映射路径）。', @db_seed_test_did, @db_seed_test_uid);

-- ----------------------------