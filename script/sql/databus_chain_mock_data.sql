-- ----------------------------
-- 数据总线示例链路种子数据（一次性，验证后可连同本文件与 mock-presets.ts 一并删除）
-- 由 tools 脚本依据 plus-ui mock-presets.ts 生成，请勿手工编辑。
-- 全部为草稿状态（status=0）；发布走前端/接口 publish，后端实时生成 EL 推 Rule-DB。
-- id 段 1762000000000100001 起；重复执行前先按 remark 或 id 清理。
-- ----------------------------
insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100001, 'script-if-booleanScript', '条件脚本与脚本（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"IF","condition":{"id":"booleanScript1","type":"NodeBooleanComponent","properties":{"tag":"booleanScript1","data":"{\\"language\\":\\"groovy\\",\\"script\\":\\"def v = databusContext.read(''$.flag''); return v == true\\"}"}},"children":[{"type":"THEN","children":[{"id":"script1","type":"NodeComponent","properties":{"tag":"script1","data":"{\\"language\\":\\"groovy\\",\\"script\\":\\"def name = databusContext.read(''$.name''); databusContext.save(''$.script1.greeting'', ''hello '' + name)\\"}"}}]},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.response1.msg\\",\\"value\\":\\"已完成\\"}"}}]}]}', 'BASIC', sysdate(), 'Groovy 脚本读写数据空间与 IF 条件脚本（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100002, 'http-anonymous-chain', 'HTTP 免授权双请求（本地 RuoYi）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/auth/code\\",\\"mappings\\":[{\\"field\\":\\"captchaEnabled\\",\\"path\\":\\"$.data.captchaEnabled\\"},{\\"field\\":\\"uuid\\",\\"path\\":\\"$.data.uuid\\",\\"required\\":false}]}"}},{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest2","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/\\",\\"headers\\":{\\"X-Demo\\":\\"databus\\"}}"}}]}', 'BASIC', sysdate(), '免授权免加密：JSON 抽取 + 纯文本响应');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100003, 'bpm-flow', 'BPM 全链路（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"processStart","type":"NodeComponent","properties":{"tag":"processStart1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"processDefId\\":\\"obj_61a4e68a43e043fa87f89530a503f9ae\\",\\"uid\\":\\"admin\\",\\"title\\":\\"申请-${$.request.code}\\"}"}},{"id":"boCreate","type":"NodeComponent","properties":{"tag":"boCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"method\\":\\"create\\",\\"bindId\\":\\"$.processStart1.processInstanceId\\",\\"uid\\":\\"admin\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"$.request.boList\\",\\"rewrite\\":{\\"strategy\\":\\"all\\",\\"path\\":\\"$.response.boList\\"}}]}"}},{"id":"fileUpload","type":"NodeComponent","properties":{"tag":"fileUpload1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"sourcePath\\":\\"$.request.files\\",\\"boId\\":\\"$.boCreate1.boResults[0].records[0].ID\\",\\"appId\\":\\"com.awspaas.user.apps.data.bus\\",\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"boItemName\\":\\"BO_FIELD_FILE\\",\\"processInstId\\":\\"$.processStart1.processInstanceId\\"}"}},{"id":"fileDownload","type":"NodeComponent","properties":{"tag":"fileDownload1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"boId\\":\\"$.boCreate1.boResults[0].records[0].ID\\",\\"fieldName\\":\\"BO_FIELD_FILE\\"}"}},{"id":"taskComplete","type":"NodeComponent","properties":{"tag":"taskComplete1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"processInstanceId\\":\\"$.processStart1.processInstanceId\\",\\"uid\\":\\"admin\\",\\"failOnError\\":false}"}}]}', 'BASIC', sysdate(), '会话→启流程→建 BO→附件上传/读回→完任务');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100004, 'bpm-bo-update', 'BPM BO 查改验证（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":10,\\"conditionSourcePath\\":\\"$.request.conditions\\"}}"}},{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"hasData1","data":"{\\"path\\":\\"$.boQuery1.records[0]\\",\\"op\\":\\"notBlank\\"}"}},"children":[{"type":"THEN","children":[{"id":"dataPatch","type":"NodeComponent","properties":{"tag":"dataPatch1","data":"{\\"target\\":\\"$.boQuery1.records[*]\\",\\"patch\\":{\\"BO_FIELD_USER\\":\\"$.request.newUser\\"}}"}},{"id":"boUpdate","type":"NodeComponent","properties":{"tag":"boUpdate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"$.boQuery1.records\\"}]}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery2","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":10,\\"conditionSourcePath\\":\\"$.request.conditions\\"}}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"查询无数据，已跳过更新\\"}"}}]}]}', 'BASIC', sysdate(), '按查询结果分流：有数据则改并验证，无则跳过');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100005, 'bpm-bo-delete', 'BPM BO 条件清理（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"boQuery","type":"NodeComponent","properties":{"tag":"boQuery1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"main\\":{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"method\\":\\"list\\",\\"maxRecord\\":50,\\"conditionSourcePath\\":\\"$.request.conditions\\"}}"}},{"id":"boDelete","type":"NodeComponent","properties":{"tag":"boDelete1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"method\\":\\"remove\\",\\"boList\\":[{\\"boName\\":\\"BO_EU_API_TEST_MAIN\\",\\"sourcePath\\":\\"$.boQuery1.records\\"}]}"}}]}', 'BASIC', sysdate(), '按条件查出记录并按 ID 批量删除');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100006, 'bpm-rds-methods', 'BPM SQL 八方法全覆盖（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"drop table if exists DATABUS_RDS_TEST\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute2","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"create table DATABUS_RDS_TEST (ID varchar(32) not null primary key, NAME varchar(64), AGE int, SALARY decimal(10,2))\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute3","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"batch\\",\\"sql\\":\\"insert into DATABUS_RDS_TEST (ID, NAME, AGE, SALARY) values (?, ?, ?, ?)\\",\\"args\\":[[\\"t1\\",\\"张三\\",30,8800.5],[\\"t2\\",\\"李四\\",25,7600],[\\"t3\\",\\"王五\\",41,12000.75]]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute4","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getString\\",\\"sql\\":\\"select NAME from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute5","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getInt\\",\\"sql\\":\\"select AGE from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t2\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute6","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getLong\\",\\"sql\\":\\"select count(*) from DATABUS_RDS_TEST\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute7","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getDouble\\",\\"sql\\":\\"select SALARY from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t3\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute8","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMap\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST where ID = ?\\",\\"args\\":[\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute9","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMaps\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST order by ID\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute10","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"update DATABUS_RDS_TEST set NAME = ? where ID = ?\\",\\"args\\":[\\"张三-改\\",\\"t1\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute11","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"batch\\",\\"sql\\":[\\"update DATABUS_RDS_TEST set AGE = AGE + 1 where ID = ''t2''\\",\\"delete from DATABUS_RDS_TEST where ID = ''t3''\\"]}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute12","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"getMaps\\",\\"sql\\":\\"select ID, NAME, AGE, SALARY from DATABUS_RDS_TEST order by ID\\"}"}},{"id":"rdsExecute","type":"NodeComponent","properties":{"tag":"rdsExecute13","data":"{\\"connectionId\\":\\"bpm-default\\",\\"rdsId\\":\\"3ed8f0e7-d7fc-451c-b73c-301a6f22fcea\\",\\"method\\":\\"update\\",\\"sql\\":\\"drop table DATABUS_RDS_TEST\\"}"}}]}', 'BASIC', sysdate(), '临时表自清理，覆盖查询写入与批量两种模式');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100007, 'bpm-idcard-to-userid', 'BPM 身份证换用户（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"idCardToUserId","type":"NodeComponent","properties":{"tag":"idCardToUserId1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"fields\\":[{\\"path\\":\\"$.request.idCards\\",\\"separator\\":\\",\\"},{\\"path\\":\\"$.request.idCardsPartial\\",\\"separator\\":\\",\\"}]}"}}]}', 'BASIC', sysdate(), '逗号分隔身份证号批量换 userId 原地写回');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100008, 'bpm-idcard-all-miss', 'BPM 身份证全未命中（需 BPM 环境）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"sessionCreate","type":"NodeComponent","properties":{"tag":"sessionCreate1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"userName\\":\\"admin\\",\\"password\\":\\"$.request.password\\"}"}},{"id":"idCardToUserId","type":"NodeComponent","properties":{"tag":"idCardToUserId1","data":"{\\"connectionId\\":\\"bpm-default\\",\\"fields\\":[{\\"path\\":\\"$.request.idCards\\",\\"separator\\":\\",\\"}]}"}}]}', 'BASIC', sysdate(), '全部身份证查无用户，节点预期抛错中断');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100009, 'local-flag', '入参开关（纯本地，无外部依赖）', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.flag\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"flag 为真\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"flag 为假\\"}"}}]}', 'BASIC', sysdate(), '按入参 flag 真假写不同赋值');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100010, 'serial-all', '串行 THEN', 1, '0', null, null, '{"type":"THEN","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://localhost:8080/auth/code\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.note\\",\\"value\\":\\"串行演示\\"}"}},{"id":"fieldMap","type":"NodeComponent","properties":{"tag":"fieldMap1","data":"{\\"mappings\\":[{\\"from\\":\\"$.httpRequest1.response.code\\",\\"to\\":\\"$.fieldMap1.code\\"}]}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"msg\\":\\"$.httpRequest1.response.msg\\",\\"dataPath\\":\\"$.fieldMap1\\"}"}}]}', 'BASIC', sysdate(), '请求→赋值→映射→响应四类真组件串联');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100011, 'then-empty', '空 THEN 占位', 1, '0', null, null, '{"type":"THEN","children":[]}', 'BASIC', sysdate(), '空 children 验证占位节点与 start 连边（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100012, 'when-parallel', '并行 WHEN', 1, '0', null, null, '{"type":"WHEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"并行 A\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"并行 B\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue3","data":"{\\"path\\":\\"$.setValue3.out\\",\\"value\\":\\"并行 C\\"}"}}]}', 'BASIC', sysdate(), '三路赋值并行，验证扇出扇入与 junction 多入边');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100013, 'if-branch', '条件 IF 双分支', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.code\\",\\"op\\":\\"eq\\",\\"value\\":200}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.result\\",\\"value\\":\\"code 命中\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.result\\",\\"value\\":\\"code 未命中\\"}"}}]}', 'BASIC', sysdate(), '按入参 code 是否等于 200 走不同赋值');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100014, 'if-empty-false', 'IF 空假分支', 1, '0', null, null, '{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.flag\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"真分支执行\\"}"}}]}', 'BASIC', sysdate(), '只有真分支，假分支为占位节点');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100015, 'switch-cases', 'SWITCH 按值选分支（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"SWITCH","properties":{"outletLabels":["caseA","caseB"]},"condition":{"id":"switchRoute","type":"NodeSwitchComponent","properties":{"tag":"switchRoute1","data":"{\\"source\\":\\"$.code\\",\\"cases\\":[{\\"value\\":\\"A\\",\\"target\\":\\"caseA\\"},{\\"value\\":\\"B\\",\\"target\\":\\"caseB\\"}]}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"命中 A 分支\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"命中 B 分支\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"msg\\":\\"路由完成\\"}"}}]}', 'BASIC', sysdate(), '按 code 值命中对应 case 分支');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100016, 'for-count', 'FOR 计数循环（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"FOR","condition":{"id":"forLoop","type":"NodeForComponent","properties":{"tag":"forLoop1","data":"{\\"count\\":3}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.forLoop1.cursor\\",\\"value\\":\\"$i\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"$.forLoop1\\"}"}}]}', 'BASIC', sysdate(), '循环 3 轮，$i 下标写入数据空间');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100017, 'while-loop', 'WHILE 循环（零次执行安全示例）', 1, '0', null, null, '{"type":"WHILE","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.flag\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"循环体执行\\"}"}}]}', 'BASIC', sysdate(), '条件为假时循环体零次执行');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100018, 'iterator-items', 'ITERATOR 迭代数组（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop1","data":"{\\"source\\":\\"$.items\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.iteratorLoop1.lastName\\",\\"value\\":\\"$.items[$i].name\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"$.iteratorLoop1\\"}"}}]}', 'BASIC', sysdate(), '迭代数组，$i 取每项名称');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100019, 'iterator-nested', 'ITERATOR 双层嵌套（纯本地）', 1, '0', null, null, '{"type":"THEN","children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop1","data":"{\\"source\\":\\"$.groups\\"}"}},"children":[{"type":"ITERATOR","condition":{"id":"iteratorLoop","type":"NodeIteratorComponent","properties":{"tag":"iteratorLoop2","data":"{\\"source\\":\\"$.groups[$i].users\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.iteratorLoop1.last\\",\\"value\\":\\"$.groups[$i].users[$j].name\\"}"}}]}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":true,\\"dataPath\\":\\"$.iteratorLoop1\\"}"}}]}', 'BASIC', sysdate(), '双层迭代：外层 $i 内层 $j');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100020, 'catch-flow', 'CATCH 异常捕获（真触发异常分支）', 1, '0', null, null, '{"type":"CATCH","children":[{"id":"httpRequest","type":"NodeComponent","properties":{"tag":"httpRequest1","data":"{\\"method\\":\\"GET\\",\\"url\\":\\"http://127.0.0.1:9999/no-such-service\\"}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"下游不可用，已兜底\\"}"}}]}', 'BASIC', sysdate(), '请求不存在端口触发异常，响应组件兜底');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100021, 'catch-empty', 'CATCH 空异常槽', 1, '0', null, null, '{"type":"CATCH","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"正常执行\\"}"}}]}', 'BASIC', sysdate(), '仅正常主体，异常槽为占位节点');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100022, 'and-logic', 'AND 与逻辑', 1, '0', null, null, '{"type":"AND","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.a\\",\\"op\\":\\"isTrue\\"}"}},{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition2","data":"{\\"path\\":\\"$.b\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', sysdate(), '两个布尔条件取与（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100023, 'or-logic', 'OR 或逻辑', 1, '0', null, null, '{"type":"OR","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.a\\",\\"op\\":\\"isTrue\\"}"}},{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition2","data":"{\\"path\\":\\"$.b\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', sysdate(), '两个布尔条件取或（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100024, 'not-logic', 'NOT 非逻辑', 1, '0', null, null, '{"type":"NOT","children":[{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.flag\\",\\"op\\":\\"isTrue\\"}"}}]}', 'BASIC', sysdate(), '对布尔条件取反（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100025, 'chain-ref', 'CHAIN 子流程引用', 1, '0', null, null, '{"type":"THEN","children":[{"id":"subChain_demo","type":"NodeComponent","properties":{"tag":"subChain_demo"}}]}', 'BASIC', sysdate(), '引用子链 subChain_demo（结构展示）');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100026, 'nested-complex', '嵌套综合', 1, '0', null, null, '{"type":"THEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue0","data":"{\\"path\\":\\"$.setValue0.out\\",\\"value\\":\\"串行头\\"}"}},{"type":"IF","condition":{"id":"condition","type":"NodeBooleanComponent","properties":{"tag":"condition1","data":"{\\"path\\":\\"$.flag\\",\\"op\\":\\"isTrue\\"}"}},"children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"IF 真\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"IF 假\\"}"}}]},{"type":"WHEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue3","data":"{\\"path\\":\\"$.setValue3.out\\",\\"value\\":\\"并行 A\\"}"}},{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue4","data":"{\\"path\\":\\"$.setValue4.out\\",\\"value\\":\\"并行 B\\"}"}}]}]}', 'BASIC', sysdate(), 'THEN(赋值, IF(flag 双分支), WHEN(并行两路))');

insert into databus_chain
  (id, chain_code, chain_name, version, status, el_expression, canvas_data, cmp_property, log_level, create_time, remark)
values
  (1762000000000100027, 'nested-catch-in-then', 'THEN 内嵌 CATCH（正常路径）', 1, '0', null, null, '{"type":"THEN","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue1","data":"{\\"path\\":\\"$.setValue1.out\\",\\"value\\":\\"串行头\\"}"}},{"type":"CATCH","children":[{"id":"setValue","type":"NodeComponent","properties":{"tag":"setValue2","data":"{\\"path\\":\\"$.setValue2.out\\",\\"value\\":\\"try 主体\\"}"}},{"id":"response","type":"NodeComponent","properties":{"tag":"response1","data":"{\\"result\\":false,\\"msg\\":\\"进入异常处理\\"}"}}]},{"id":"response","type":"NodeComponent","properties":{"tag":"response2","data":"{\\"result\\":true,\\"msg\\":\\"主流程完成\\"}"}}]}', 'BASIC', sysdate(), 'THEN 内嵌 CATCH，异常槽不触发');
