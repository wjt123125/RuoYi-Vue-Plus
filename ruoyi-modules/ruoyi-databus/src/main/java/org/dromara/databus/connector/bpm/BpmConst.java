package org.dromara.databus.connector.bpm;

/**
 * BPM HTTP 连接器常量：4 个 BPM 端点 cmd 字符串（决策 9.1.2）。
 *
 * <p>BPM 端 URL 形如 {@code http://localhost:8088/portal/r/jd?cmd=<MAPPING_VALUE>}，
 * 不带 sid（因 session=false 无鉴权，决策 9.1.2 + 9.1.3）。
 *
 * <p>对应 BPM 端 DataBusConnectorController 的 4 个 @Mapping value。
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

    /** BPM 端 ResponseObject 的字段名（fastjson 序列化后的 key） */
    public static final String RESP_RESULT = "result";
    public static final String RESP_DATA = "data";
    public static final String RESP_MSG = "msg";
    public static final String RESP_ERROR_CODE = "errorCode";
    public static final String RESP_OK = "ok";
}
