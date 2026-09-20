package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * SESSION_CREATE 请求入参（对应 BPM 端 {@code SessionCreateRequest}）。
 * <p>
 * 所有字段从 Component 层显式传入（决策 §1：上下文原子化，BPM 端无共享上下文）。
 *
 * @deprecated 旧 jd 通道时期的参数类，当前无引用（SESSION_CREATE 实际使用
 * {@link SessionCreateRequest}）；保留待后续清理。
 *
 * @author databus
 */
@Deprecated
@Data
public class BpmSessionCreateParam {

    /** BPM 用户 ID，必填 */
    private String userName;

    /** 用户密码，必填 */
    private String password;

    /** 客户端真实 IP，可选（默认 0.0.0.0），由总线层从 HTTP 请求头/Connection 配置取后塞入 */
    private String clientIp = "0.0.0.0";

    /** 语言，可选（默认 cn） */
    private String lang = "cn";

    /** 设备类型，可选（默认 PC） */
    private String device = "PC";

    /** IP 白名单，可选。由总线层从 Connection 配置传入，与 BPM 用户档案绑定 IP 合并后参与校验 */
    private List<String> ipWhiteList;
}
