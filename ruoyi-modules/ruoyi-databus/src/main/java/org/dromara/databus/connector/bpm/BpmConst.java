package org.dromara.databus.connector.bpm;

/**
 * BPM HTTP 连接器常量：BPM 端点 cmd 字符串。
 *
 * <p>所有 cmd 走平台 OpenAPI 网关：{@code http://<host>:<port>/portal/openapi}，
 * POST application/x-www-form-urlencoded，公共参数 cmd/access_key/timestamp/sig_method/format + sig
 * （access_key + HmacMD5 签名，见 {@link BpmOpenApiSigner}）。
 *
 * <p>对应 BPM 端 DataBusConnectorController 的 @Mapping value（type=OPENAPI）。
 *
 * @author databus
 */
public final class BpmConst {

    private BpmConst() {
    }

    /** SESSION_CREATE 端点 cmd（创建 BPM 客户端 Session） */
    public static final String CMD_SESSION_CREATE = "com.awspaas.databus.connector.SESSION_CREATE";

    /** BO_CREATE 端点 cmd（创建 BO 数据） */
    public static final String CMD_BO_CREATE = "com.awspaas.databus.connector.BO_CREATE";

    /** BO_UPDATE 端点 cmd（更新 BO 数据） */
    public static final String CMD_BO_UPDATE = "com.awspaas.databus.connector.BO_UPDATE";

    /** BO_DELETE 端点 cmd（删除 BO 数据） */
    public static final String CMD_BO_DELETE = "com.awspaas.databus.connector.BO_DELETE";

    /** BO_QUERY 端点 cmd（查询 BO 数据） */
    public static final String CMD_BO_QUERY = "com.awspaas.databus.connector.BO_QUERY";

    /** PROCESS_START 端点 cmd（启动流程） */
    public static final String CMD_PROCESS_START = "com.awspaas.databus.connector.PROCESS_START";

    /** PROCESS_TERMINATE 端点 cmd（终止流程） */
    public static final String CMD_PROCESS_TERMINATE = "com.awspaas.databus.connector.PROCESS_TERMINATE";

    /** TASK_COMPLETE 端点 cmd（提交任务） */
    public static final String CMD_TASK_COMPLETE = "com.awspaas.databus.connector.TASK_COMPLETE";

    /** RDS_EXECUTE 端点 cmd（执行 BPM RDS 数据源 SQL） */
    public static final String CMD_RDS_EXECUTE = "com.awspaas.databus.connector.RDS_EXECUTE";

    /** IDCARD_TO_USERID 端点 cmd（身份证号批量换 userId） */
    public static final String CMD_IDCARD_TO_USERID = "com.awspaas.databus.connector.IDCARD_TO_USERID";

    /** FILE_UPLOAD 端点 cmd（base64 文件上传到 BO 附件字段） */
    public static final String CMD_FILE_UPLOAD = "com.awspaas.databus.connector.FILE_UPLOAD";

    /** FILE_DOWNLOAD 端点 cmd（读取 BO 附件字段文件转 base64） */
    public static final String CMD_FILE_DOWNLOAD = "com.awspaas.databus.connector.FILE_DOWNLOAD";

    /** PING 端点 cmd（网关连通性自检，无业务参数；测试连接使用） */
    public static final String CMD_PING = "com.awspaas.databus.connector.PING";

    /** BPM 端响应信封的字段名（ApiResponse fastjson 序列化后的 key） */
    public static final String RESP_RESULT = "result";
    public static final String RESP_DATA = "data";
    public static final String RESP_MSG = "msg";
    public static final String RESP_ERROR_CODE = "errorCode";
    public static final String RESP_OK = "ok";
}
