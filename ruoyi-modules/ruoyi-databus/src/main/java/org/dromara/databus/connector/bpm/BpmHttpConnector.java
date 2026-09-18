package org.dromara.databus.connector.bpm;

import lombok.extern.slf4j.Slf4j;
import org.dromara.databus.component.protocol.HttpRequestComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.Connector;
import org.dromara.databus.connector.ConnectorDescriptor;
import org.dromara.databus.connector.ConnectorException;
import org.dromara.databus.connector.bpm.dto.BoCreateRequest;
import org.dromara.databus.connector.bpm.dto.ProcessStartRequest;
import org.dromara.databus.connector.bpm.dto.SessionCreateRequest;
import org.dromara.databus.connector.bpm.dto.TaskCompleteRequest;
import org.dromara.databus.context.JsonCodec;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * BPM HTTP 连接器：通过 HTTP 调用 BPM 端总线 app 的 4 个 @Mapping 端点。
 *
 * <p>三层物料模型（决策 2.2）的第一层实现：物料市场注册（{@code @Component} 自动注册到 {@link org.dromara.databus.connector.ConnectorRegistry}），
 * 描述能力清单（{@link #describe()}）驱动前端 cmp-defs 物料与连接管理表单。
 *
 * <p>URL 拼接（决策 9.1.2 + 9.3）：{@code cfg.getEndpoint() + "/portal/r/jd?cmd=" + BpmConst.CMD_XXX}
 * （不带 sid，因 session=false 无鉴权）。
 *
 * <p>本类自己暴露 4 操作方法（决策 9.3：Connector 接口不包含 4 操作方法，
 * 因不同 Connector 操作集合不同；具体业务方法在具体实现类声明）：
 * <ul>
 *   <li>{@link #createSession(Connection, SessionCreateRequest)}</li>
 *   <li>{@link #boCreate(Connection, BoCreateRequest)}</li>
 *   <li>{@link #processStart(Connection, ProcessStartRequest)}</li>
 *   <li>{@link #taskComplete(Connection, TaskCompleteRequest)}</li>
 * </ul>
 *
 * <p>HTTP 调用用 Spring {@link RestClient}（与 {@link HttpRequestComponent} 范式一致）。
 * 错误时解析 BPM 端 ResponseObject 的 result/errorCode/msg 转 {@link ConnectorException}。
 *
 * <p>1D-P0 不实现重试（{@link BpmHttpConnectionCfg#getRetryCount()} 保留字段供未来）；
 * 不实现鉴权（{@code session=false} 无鉴权，待办见 work-state.md）。
 *
 * @author databus
 */
@Slf4j
@Component
public class BpmHttpConnector implements Connector {

    /** Connector 类型标识，全局唯一 */
    public static final String TYPE = "bpmHttp";

    /** BPM 端 ResponseObject 的字段名（与 BPM 端 BpmConst 保持一致语义） */
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
        desc.getConfigSchema().put("authUser",
                ConnectorDescriptor.ConfigField.ofString("默认 BPM 用户（连接级默认 uid）", false, null));
        // authPassword 标记敏感：连接管理服务据此拆入 credentials 加密列，不落明文 config
        desc.getConfigSchema().put("authPassword",
                ConnectorDescriptor.ConfigField.ofString("默认 BPM 密码", false, null).sensitive());
        desc.getConfigSchema().put("timeoutMs",
                ConnectorDescriptor.ConfigField.ofInt("HTTP 超时（毫秒）", false, 30000));
        desc.getConfigSchema().put("retryCount",
                ConnectorDescriptor.ConfigField.ofInt("失败重试次数（1D-P0 未实现，保留字段）", false, 0));
        desc.getConfigSchema().put("ipWhiteList",
                ConnectorDescriptor.ConfigField.ofArray("IP 白名单", false, null));

        // 4 个操作定义
        desc.getOperations().add(buildOperation("createSession", "创建会话",
                "sessionCreate", List.of("sessionId", "idCard")));
        desc.getOperations().add(buildOperation("boCreate", "创建 BO 数据",
                "boCreate", List.of("boResults")));
        desc.getOperations().add(buildOperation("processStart", "启动流程",
                "processStart", List.of("processInstanceId", "isProcess", "activeTaskIds")));
        desc.getOperations().add(buildOperation("taskComplete", "提交任务",
                "taskComplete", List.of("processInstanceId", "processEnded",
                        "completedTaskIds", "failedTaskIds", "failedErrors")));
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
     * 测试连接：调 SESSION_CREATE 端点，传 authUser/authPassword，看返回 result=ok 即视为连通。
     * <p>不实际创建会话也不保留 sid，仅验证 BPM 容器可达 + 鉴权信息正确。
     * <p>若 BPM 端用户名密码错误，BPM 端会抛 BpmConnectorException 并通过 ResponseObject 返回错误码。
     */
    @Override
    public String testConnection(Connection connection) {
        BpmHttpConnectionCfg cfg = fromConnection(connection);
        if (cfg.getAuthUser() == null || cfg.getAuthUser().isBlank()) {
            throw new ConnectorException("CONNECTOR_CFG_MISSING",
                    "测试连接缺少 authUser，请在连接配置中填写 BPM 用户名");
        }
        SessionCreateRequest req = new SessionCreateRequest();
        req.setUserName(cfg.getAuthUser());
        req.setPassword(cfg.getAuthPassword());
        req.setClientIp("0.0.0.0");
        req.setIpWhiteList(cfg.getIpWhiteList() != null ? cfg.getIpWhiteList() : List.of());
        Object result = createSession(connection, req);
        // 仅返回简洁成功文案给用户；BPM 端响应（sessionId/idCard 等敏感字段）由 callBpm 的 log.info 记录留痕
        return "BPM 连接成功";
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
     * PROCESS_START 操作：调 BPM 端 PROCESS_START 端点。
     *
     * @return BPM 端响应的 data 字段 {@code {processInstanceId, isProcess, activeTaskIds}}
     */
    public Object processStart(Connection connection, ProcessStartRequest request) {
        return callBpm(connection, BpmConst.CMD_PROCESS_START, request);
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
     * 通用 BPM HTTP 调用：序列化请求 DTO → POST → 解析 BPM 端 ResponseObject → result=ok 返回 data / 失败抛 ConnectorException。
     */
    @SuppressWarnings("unchecked")
    private Object callBpm(Connection connection, String cmd, Object requestDto) {
        BpmHttpConnectionCfg cfg = fromConnection(connection);
        if (cfg.getEndpoint() == null || cfg.getEndpoint().isBlank()) {
            throw new ConnectorException("CONNECTOR_CFG_MISSING",
                    "BPM 连接 endpoint 未配置（connectionId=" + connection.getId() + "）");
        }
        String url = cfg.getEndpoint().replaceAll("/+$", "") + "/portal/r/jd?cmd=" + cmd;
        log.info("[bpmHttp] 调用 {} 入参={}", cmd, JsonCodec.toJson(requestDto));

        String responseText;
        try {
            responseText = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonCodec.toJson(requestDto))
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            log.error("[bpmHttp] HTTP 调用失败 cmd={} url={}", cmd, url, e);
            throw new ConnectorException("BPM_HTTP_CALL_FAILED",
                    "BPM 服务连接失败: " + e.getMessage(), e);
        }

        // 解析 BPM 端 ResponseObject
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
