package org.dromara.databus.connector.bpm.dto;

import lombok.Data;

import java.util.List;

/**
 * SESSION_CREATE 端点入参（与 BPM 端 {@code com.awspaas.databus.connector.dto.SessionCreateRequest} 字段一致）。
 *
 * <p>所有字段从 HTTP 请求体显式传入（BPM 端无共享上下文，决策 1：上下文原子化）。
 * ipWhiteList 旧机制随 jd 免会话通道删除，组件固定传空列表（网关签名鉴权已替代）。
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

    /** IP 白名单，旧 jd 免会话机制遗留字段，组件固定传空列表（网关签名鉴权已替代） */
    private List<String> ipWhiteList;
}
