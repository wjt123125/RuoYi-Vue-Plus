package org.dromara.databus.connector.bpm;

import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.component.protocol.HttpRequestComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.bpm.dto.BoCreateRequest;
import org.dromara.databus.connector.bpm.dto.BoDeleteRequest;
import org.dromara.databus.connector.bpm.dto.BoQueryRequest;
import org.dromara.databus.connector.bpm.dto.BoUpdateRequest;
import org.dromara.databus.connector.bpm.dto.FileDownloadRequest;
import org.dromara.databus.connector.bpm.dto.FileUploadRequest;
import org.dromara.databus.connector.bpm.dto.IdCardToUserIdRequest;
import org.dromara.databus.connector.bpm.dto.ProcessStartRequest;
import org.dromara.databus.connector.bpm.dto.ProcessTerminateRequest;
import org.dromara.databus.connector.bpm.dto.RdsExecuteRequest;
import org.dromara.databus.connector.bpm.dto.SessionCreateRequest;
import org.dromara.databus.connector.bpm.dto.TaskCompleteRequest;
import org.dromara.databus.context.JsonCodec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * BPM HTTP 连接器：通过 HTTP 调用 BPM 端总线 app 的 12 个 @Mapping 端点。
 *
 * <p>三层物料模型（决策 2.2）的第一层实现：物料市场注册（{@code @Component} 自动注册到 {@link org.dromara.databus.connector.ConnectorRegistry}），
 * 描述能力清单（{@link #describe()}）驱动前端 cmp-defs 物料与连接管理表单。
 *
 * <p>URL 拼接：{@code cfg.getEndpoint() + "/portal/openapi"}（固定入口，cmd 为签名表单参数），
 * POST application/x-www-form-urlencoded，access_key + HmacMD5 签名（{@link BpmOpenApiSigner}）。
 *
 * <p>本类自己暴露 10 个操作方法（决策 9.3：Connector 接口不包含操作方法，
 * 因不同 Connector 操作集合不同；具体业务方法在具体实现类声明）：
 * <ul>
 *   <li>{@link #createSession(Connection, SessionCreateRequest)}</li>
 *   <li>{@link #boCreate(Connection, BoCreateRequest)}</li>
 *   <li>{@link #boUpdate(Connection, BoUpdateRequest)}</li>
 *   <li>{@link #boDelete(Connection, BoDeleteRequest)}</li>
 *   <li>{@link #boQuery(Connection, BoQueryRequest)}</li>
 *   <li>{@link #processStart(Connection, ProcessStartRequest)}</li>
 *   <li>{@link #processTerminate(Connection, ProcessTerminateRequest)}</li>
 *   <li>{@link #taskComplete(Connection, TaskCompleteRequest)}</li>
 *   <li>{@link #rdsExecute(Connection, RdsExecuteRequest)}</li>
 *   <li>{@link #idCardToUserId(Connection, IdCardToUserIdRequest)}</li>
 *   <li>{@link #fileUpload(Connection, FileUploadRequest)}</li>
 *   <li>{@link #fileDownload(Connection, FileDownloadRequest)}</li>
 * </ul>
 *
 * <p>HTTP 调用用 Spring {@link RestClient}（与 {@link HttpRequestComponent} 范式一致）。
 * 错误时解析 BPM 端 ApiResponse 的 result/errorCode/msg 转 {@link ConnectorException}。
 *
 * <p>1D-P0 不实现重试（{@link BpmHttpConnectionCfg#getRetryCount()} 保留字段供未来）。
 *
 * <p>鉴权：唯一通道为平台 /portal/openapi 签名网关（2026-09-20 全量切换，旧 jd 免会话通道已删）。
 * 协议细节封在本类内，组件层零感知。设计文档：docs/wiki/databus-bpm-endpoint-auth.md。
 *
 * @author databus
 */
@Slf4j
@Component
public class BpmHttpConnector implements Connector {

    /** Connector 类型标识，全局唯一 */
    public static final String TYPE = "bpmHttp";

    /** BPM 端 ApiResponse 的字段名（与 BPM 端 BpmConst 保持一致语义） */
    private static final String RESP_RESULT = "result";
    private static final String RESP_DATA = "data";
    private static final String RESP_MSG = "msg";
    private static final String RESP_ERROR_CODE = "errorCode";
    private static final String RESP_OK = "ok";

    /**
     * 本档用默认 RestClient（JDK HttpClient 工厂），超时通过 configure 方法按连接配置生效。
     * 不配置连接池/拦截器（与 HttpRequestComponent 一致范式）。
     */
    private final RestClient restClient = RestClient.create();

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ConnectorDescriptor describe() {
        ConnectorDescriptor desc = new ConnectorDescriptor();
        desc.setType(TYPE);
        desc.setLabel("BPM HTTP 连接器");
        desc.setIcon("ph:plugs");

        // 连接配置 schema（驱动连接管理页表单动态渲染）
        desc.getConfigSchema().put("endpoint",
                ConnectorDescriptor.ConfigField.ofString("BPM 容器地址", true, null));
        desc.getConfigSchema().put("accessKey",
                ConnectorDescriptor.ConfigField.ofString(
                        "OpenAPI access_key（CC 身份策略访问凭证）", true, null));
        // apiSecret 标记敏感：连接管理服务据此拆入 credentials 加密列，不落明文 config
        desc.getConfigSchema().put("apiSecret",
                ConnectorDescriptor.ConfigField.ofString(
                        "OpenAPI secret（CC 身份策略私钥）", true, null).sensitive());
        desc.getConfigSchema().put("timeoutMs",
                ConnectorDescriptor.ConfigField.ofInt("HTTP 超时（毫秒）", false, 30000));
        desc.getConfigSchema().put("retryCount",
                ConnectorDescriptor.ConfigField.ofInt("失败重试次数（1D-P0 未实现，保留字段）", false, 0));

        // 10 个操作定义
        desc.getOperations().add(buildOperation("createSession", "创建会话",
                "sessionCreate", List.of("sessionId", "idCard")));
        desc.getOperations().add(buildOperation("boCreate", "创建 BO 数据",
                "boCreate", List.of("boResults")));
        desc.getOperations().add(buildOperation("boUpdate", "更新 BO 数据",
                "boUpdate", List.of("boResults")));
        desc.getOperations().add(buildOperation("boDelete", "删除 BO 数据",
                "boDelete", List.of("boResults")));
        desc.getOperations().add(buildOperation("boQuery", "查询 BO 数据",
                "boQuery", List.of("boName", "method", "records", "count")));
        desc.getOperations().add(buildOperation("processStart", "启动流程",
                "processStart", List.of("processInstanceId", "isProcess", "activeTaskIds")));
        desc.getOperations().add(buildOperation("processTerminate", "终止流程",
                "processTerminate", List.of("processInstanceId", "terminated", "alreadyEnded")));
        desc.getOperations().add(buildOperation("taskComplete", "提交任务",
                "taskComplete", List.of("processInstanceId", "processEnded",
                        "completedTaskIds", "failedTaskIds", "failedErrors")));
        desc.getOperations().add(buildOperation("rdsExecute", "RDS SQL 执行",
                "rdsExecute", List.of("method", "data")));
        desc.getOperations().add(buildOperation("idCardToUserId", "身份证换 userId",
                "idCardToUserId", List.of("userIds", "matched", "missed")));
        desc.getOperations().add(buildOperation("fileUpload", "上传附件",
                "fileUpload", List.of("uploadedCount", "files")));
        desc.getOperations().add(buildOperation("fileDownload", "下载附件",
                "fileDownload", List.of("fileCount", "files")));
        return desc;
    }

    private ConnectorDescriptor.OperationDef buildOperation(String name, String label,
                                                            String componentId, List<String> outputs) {
        ConnectorDescriptor.OperationDef op = new ConnectorDescriptor.OperationDef();
        op.setName(name);
        op.setLabel(label);
        op.setComponentId(componentId);
        op.setOutputs(outputs);
        return op;
    }

    /**
     * 测试连接：调网关 PING 端点（无业务参数），验签通过即网关可达、access_key/secret 正确。
     * <p>若密钥错误或网关不可达，BPM 端返回错误码/HTTP 异常，经 callBpm 解析后抛 ConnectorException。
     */
    @Override
    public String testConnection(Connection connection) {
        callBpm(connection, BpmConst.CMD_PING, null);
        return "BPM OpenAPI 网关连接成功";
    }

    /**
     * SESSION_CREATE 操作：调 BPM 端 SESSION_CREATE 端点。
     *
     * @return BPM 端响应的 data 字段 {@code {sessionId, idCard}}
     */
    public Object createSession(Connection connection, SessionCreateRequest request) {
        return callBpm(connection, BpmConst.CMD_SESSION_CREATE, request);
    }

    /**
     * BO_CREATE 操作：调 BPM 端 BO_CREATE 端点。
     *
     * @return BPM 端响应的 data 字段 {@code {boResults: [...]}}
     */
    public Object boCreate(Connection connection, BoCreateRequest request) {
        return callBpm(connection, BpmConst.CMD_BO_CREATE, request);
    }

    /**
     * BO_UPDATE 操作：调 BPM 端 BO_UPDATE 端点（records 每条必须含 ID）。
     *
     * @return BPM 端响应的 data 字段 {@code {boResults: [{boName, updatedCount}, ...]}}
     */
    public Object boUpdate(Connection connection, BoUpdateRequest request) {
        return callBpm(connection, BpmConst.CMD_BO_UPDATE, request);
    }

    /**
     * BO_DELETE 操作：调 BPM 端 BO_DELETE 端点（method=remove/removeByBindId）。
     *
     * @return BPM 端响应的 data 字段 {@code {boResults: [{boName, removedCount}, ...]}}
     */
    public Object boDelete(Connection connection, BoDeleteRequest request) {
        return callBpm(connection, BpmConst.CMD_BO_DELETE, request);
    }

    /**
     * BO_QUERY 操作：调 BPM 端 BO_QUERY 端点（list/listPage/count + 关联表/子表挂载）。
     *
     * @return BPM 端响应的 data 字段 {@code {boName, method, records|count}}
     */
    public Object boQuery(Connection connection, BoQueryRequest request) {
        return callBpm(connection, BpmConst.CMD_BO_QUERY, request);
    }

    /**
     * PROCESS_START 操作：调 BPM 端 PROCESS_START 端点。
     *
     * @return BPM 端响应的 data 字段 {@code {processInstanceId, isProcess, activeTaskIds}}
     */
    public Object processStart(Connection connection, ProcessStartRequest request) {
        return callBpm(connection, BpmConst.CMD_PROCESS_START, request);
    }

    /**
     * PROCESS_TERMINATE 操作：调 BPM 端 PROCESS_TERMINATE 端点。
     * 流程已结束时 BPM 端返回 terminated=false + alreadyEnded=true（幂等，非错误）。
     *
     * @return BPM 端响应的 data 字段 {@code {processInstanceId, terminated, alreadyEnded}}
     */
    public Object processTerminate(Connection connection, ProcessTerminateRequest request) {
        return callBpm(connection, BpmConst.CMD_PROCESS_TERMINATE, request);
    }

    /**
     * TASK_COMPLETE 操作：调 BPM 端 TASK_COMPLETE 端点。
     *
     * @return BPM 端响应的 data 字段 {@code {processInstanceId, processEnded, completedTaskIds, failedTaskIds, failedErrors}}
     */
    public Object taskComplete(Connection connection, TaskCompleteRequest request) {
        return callBpm(connection, BpmConst.CMD_TASK_COMPLETE, request);
    }

    /**
     * RDS_EXECUTE 操作：执行 BPM 后台注册的 RDS 数据源 SQL。
     *
     * @return BPM 端响应的 data 字段 {@code {method, data}}，data 为标量/Map/List/影响行数/int[]
     */
    public Object rdsExecute(Connection connection, RdsExecuteRequest request) {
        return callBpm(connection, BpmConst.CMD_RDS_EXECUTE, request);
    }

    /**
     * IDCARD_TO_USERID 操作：身份证号批量换 userId（BPM 端查 ORGUSER.EXT1）。
     *
     * @return BPM 端响应的 data 字段 {@code {userIds, matched, missed}}
     */
    public Object idCardToUserId(Connection connection, IdCardToUserIdRequest request) {
        return callBpm(connection, BpmConst.CMD_IDCARD_TO_USERID, request);
    }

    /**
     * FILE_UPLOAD 操作：base64 文件列表上传到 BO 记录附件字段。
     *
     * @return BPM 端响应的 data 字段 {@code {uploadedCount, files: [{fileName, fileSize, id?}]}}
     */
    public Object fileUpload(Connection connection, FileUploadRequest request) {
        return callBpm(connection, BpmConst.CMD_FILE_UPLOAD, request);
    }

    /**
     * FILE_DOWNLOAD 操作：读取 BO 记录附件字段全部文件转 base64。
     *
     * @return BPM 端响应的 data 字段 {@code {fileCount, files: [{id, fileName, securityLevel, fileSize, createUser, fileContent}]}}
     */
    public Object fileDownload(Connection connection, FileDownloadRequest request) {
        return callBpm(connection, BpmConst.CMD_FILE_DOWNLOAD, request);
    }

    /**
     * 通用 BPM 网关调用：POST form-urlencoded {@code /portal/openapi}，
     * access_key + HmacMD5 签名（{@link BpmOpenApiSigner}）；
     * 响应信封 ApiResponse（result/errorCode/msg/data）由 {@link #parseBpmResponse} 解析：
     * result=ok 返回 data；失败抛 ConnectorException。
     *
     * @param requestDto 请求 DTO；允许为 null（如 PING 无业务参数）
     */
    @SuppressWarnings("unchecked")
    private Object callBpm(Connection connection, String cmd, Object requestDto) {
        BpmHttpConnectionCfg cfg = fromConnection(connection);
        if (cfg.getEndpoint() == null || cfg.getEndpoint().isBlank()) {
            throw new ConnectorException("CONNECTOR_CFG_MISSING",
                    "BPM 连接 endpoint 未配置（connectionId=" + connection.getId() + "）");
        }
        if (cfg.getAccessKey() == null || cfg.getAccessKey().isBlank()
                || cfg.getApiSecret() == null || cfg.getApiSecret().isBlank()) {
            throw new ConnectorException("CONNECTOR_CFG_MISSING",
                    "BPM 连接 accessKey/apiSecret 未配置（connectionId=" + connection.getId() + "）");
        }
        String url = cfg.getEndpoint().replaceAll("/+$", "") + "/portal/openapi";
        String bodyJson = requestDto == null ? null : JsonCodec.toJson(requestDto);
        Map<String, String> form = BpmOpenApiSigner.buildSignedForm(
                cmd, bodyJson, cfg.getAccessKey(), cfg.getApiSecret());
        log.info("[bpmHttp] 调用 {} 入参={}", cmd, bodyJson);

        String responseText;
        try {
            responseText = restClient.post()
                    .uri(url)
                    .contentType(MediaType.parseMediaType(
                            "application/x-www-form-urlencoded;charset=UTF-8"))
                    .body(BpmOpenApiSigner.toFormUrlEncoded(form))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("[bpmHttp] HTTP 调用失败 cmd={} url={}", cmd, url, e);
            throw new ConnectorException("BPM_HTTP_CALL_FAILED",
                    "BPM 服务连接失败: " + e.getMessage(), e);
        }

        return parseBpmResponse(cmd, responseText);
    }

    /**
     * 解析 BPM 端 ApiResponse 响应信封（字段与旧 ResponseObject 同构）。
     */
    @SuppressWarnings("unchecked")
    private Object parseBpmResponse(String cmd, String responseText) {
        Object parsed = JsonCodec.parse(responseText);
        if (!(parsed instanceof Map<?, ?> respMap)) {
            log.error("[bpmHttp] BPM 端响应非 JSON 对象 cmd={} raw={}", cmd, responseText);
            throw new ConnectorException("BPM_RESPONSE_INVALID",
                    "BPM 端响应非 JSON 对象: " + responseText);
        }
        String result = String.valueOf(((Map<String, Object>) respMap).getOrDefault(RESP_RESULT, ""));
        if (!RESP_OK.equals(result)) {
            String errorCode = stringOf(((Map<String, Object>) respMap).get(RESP_ERROR_CODE));
            String msg = stringOf(((Map<String, Object>) respMap).get(RESP_MSG));
            String code = errorCode.isEmpty() ? "BPM_" + cmd + "_FAILED" : errorCode;
            log.error("[bpmHttp] BPM 端返回失败 cmd={} code={} msg={}", cmd, code, msg);
            // 用户可见消息直接用 BPM 端返回的 msg（已是业务文案）；cmd 仅日志留痕，不拼进异常消息
            throw new ConnectorException(code,
                    msg.isEmpty() ? "BPM 端调用失败: " + result : msg);
        }
        Object data = ((Map<String, Object>) respMap).get(RESP_DATA);
        log.info("[bpmHttp] 调用成功 cmd={} data={}", cmd, JsonCodec.toJson(data));
        return data;
    }

    /**
     * 把 Connection.config 转换为强类型 BPM 配置（Lombok @Data POJO，Jackson 反序列化）。
     */
    public BpmHttpConnectionCfg fromConnection(Connection connection) {
        Map<String, Object> config = connection.getConfig();
        if (config == null || config.isEmpty()) {
            throw new ConnectorException("CONNECTOR_CFG_MISSING",
                    "BPM 连接 config 为空（connectionId=" + connection.getId() + "）");
        }
        // 用 Jackson 把 Map 反序列化为 POJO（与 ruoyi-databus 全栈一致）
        return JsonCodec.convertValue(config, BpmHttpConnectionCfg.class);
    }

    private static String stringOf(Object value) {
        return value == null ? "" : value.toString();
    }
}
