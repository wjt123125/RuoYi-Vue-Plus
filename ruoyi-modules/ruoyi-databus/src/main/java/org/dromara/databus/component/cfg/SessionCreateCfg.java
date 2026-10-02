package org.dromara.databus.component.cfg;

import lombok.Data;
import org.dromara.databus.component.schema.annotation.DatabusProp;
import org.dromara.databus.component.schema.enums.ExprRole;
import org.dromara.databus.component.schema.enums.WidgetKind;

/**
 * SESSION_CREATE 组件（sessionCreate）的节点参数。
 * <pre>
 * {
 *   "connectionId": "bpm-default",
 *   "userName": "admin",
 *   "password": "{{ $.request.password }}",
 *   "clientIp": "0.0.0.0",
 *   "lang": "cn",
 *   "device": "PC"
 * }
 * </pre>
 * 字符串字段支持字面量与 {@code {{ $.路径 }}} 表达式，组件解析后传入 BPM 端 SESSION_CREATE 端点。
 * 网关签名鉴权（/portal/openapi）已在连接层完成；请求体 ipWhiteList 旧机制随 jd 通道删除，
 * 组件固定传空列表，无需在此配置。
 *
 * @author databus
 */
@Data
public class SessionCreateCfg {

    /** 连接实例 ID（必填），引用连接管理页配置的 BPM 连接 */
    @DatabusProp(label = "连接", required = true, widget = WidgetKind.CONNECTION_SELECT, order = 1)
    private String connectionId;

    /** BPM 用户 ID（必填） */
    @DatabusProp(label = "用户 ID", required = true, exprRole = ExprRole.DATA, order = 2)
    private String userName;

    /** 用户密码（必填） */
    @DatabusProp(label = "密码", required = true, widget = WidgetKind.PASSWORD, exprRole = ExprRole.DATA, order = 3)
    private String password;

    /** 客户端真实 IP（可选，默认 0.0.0.0，BPM 端校验 IP 白名单时用） */
    @DatabusProp(label = "客户端 IP", exprRole = ExprRole.DATA, order = 4, placeholder = "0.0.0.0")
    private String clientIp;

    /** 语言（可选，默认 cn） */
    @DatabusProp(label = "语言", exprRole = ExprRole.DATA, order = 5, placeholder = "cn")
    private String lang;

    /** 设备类型（可选，默认 PC） */
    @DatabusProp(label = "设备类型", exprRole = ExprRole.DATA, order = 6, placeholder = "PC")
    private String device;
}
