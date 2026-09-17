package org.dromara.databus.component.bpm;

import lombok.Data;

/**
 * SESSION_CREATE 组件（sessionCreate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "userName": "admin",
 *   "password": "$.request.password",
 *   "clientIp": "0.0.0.0",
 *   "lang": "cn",
 *   "device": "PC"
 * }
 * </pre>
 * 字符串字段支持裸路径 / 混合字符串 / 字面量，组件解析后传入 BPM 端 SESSION_CREATE 端点。
 * ipWhiteList 不在此配置，由组件从 Connection 配置（BpmHttpConnectionCfg）自动塞入请求体。
 *
 * @author databus
 */
@Data
public class SessionCreateCfg {

    /** 连接实例 ID（必填），引用连接管理页配置的 BPM 连接 */
    private String connectionId;

    /** BPM 用户 ID（必填） */
    private String userName;

    /** 用户密码（必填） */
    private String password;

    /** 客户端真实 IP（可选，默认 0.0.0.0，BPM 端校验 IP 白名单时用） */
    private String clientIp;

    /** 语言（可选，默认 cn） */
    private String lang;

    /** 设备类型（可选，默认 PC） */
    private String device;
}
