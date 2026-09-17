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

    /** PROCESS_START 端点 cmd（启动流程） */
    public static final String CMD_PROCESS_START = "com.awspaas.databus.connector.PROCESS_START";

    /** TASK_COMPLETE 端点 cmd（提交任务） */
    public static final String CMD_TASK_COMPLETE = "com.awspaas.databus.connector.TASK_COMPLETE";

    /** BPM 端 ResponseObject 的字段名（fastjson 序列化后的 key） */
    public static final String RESP_RESULT = "result";
    public static final String RESP_DATA = "data";
    public static final String RESP_MSG = "msg";
    public static final String RESP_ERROR_CODE = "errorCode";
    public static final String RESP_OK = "ok";
}
