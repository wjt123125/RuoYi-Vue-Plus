package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * SESSION_CREATE 端点入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.SessionCreateRequest} 字段一致）。
 *
 * <p>所有字段从 HTTP 请求体显式传入（BPM 端无共享上下文，决策 1：上下文原子化）。
 * ipWhiteList 由总线层（BpmHttpConnectionCfg）配置后塞入请求体，
 * 而非由 BPM 端从用户档案表查（决策 9.1.7：clientIp + ipWhiteList 均由总线层传入）。
 *
 * @author databus
 */
@Data
public class SessionCreateRequest {

    /** BPM 用户 ID，必填 */
    private String userName;

    /** 用户密码，必填 */
    private String password;

    /** 客户端真实 IP，可选（默认 0.0.0.0）。由总线层从 HTTP 请求头/Connection 配置取后塞入请求体 */
    private String clientIp = "0.0.0.0";

    /** 语言，可选（默认 cn） */
    private String lang = "cn";

    /** 设备类型，可选（默认 PC） */
    private String device = "PC";

    /** IP 白名单，可选。由总线层从 Connection 配置传入，与 BPM 用户档案绑定 IP 合并后参与校验 */
    private List<String> ipWhiteList;
}
