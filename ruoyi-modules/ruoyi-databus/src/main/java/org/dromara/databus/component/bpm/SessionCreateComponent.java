package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnectionCfg;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.SessionCreateRequest;

import java.util.List;
import java.util.Map;

/**
 * SESSION_CREATE 组件（注册名 {@code sessionCreate}）。
 * <p>
 * 调 BPM 端 SESSION_CREATE 端点，创建 BPM 客户端会话。响应 {@code {sessionId, idCard}}
 * 按字段平铺写入数据空间 {@code $.<tag>.sessionId} / {@code $.<tag>.idCard}。
 *
 * <p>ipWhiteList 由本组件从 {@link BpmHttpConnectionCfg}（Connection 配置）取出后塞入
 * {@link SessionCreateRequest}，BPM 端校验 clientIp 是否在白名单内（详见决策 9.1.7）。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("sessionCreate")
public class SessionCreateComponent extends DatabusNodeComponent {

    private static final String DEFAULT_CLIENT_IP = "0.0.0.0";
    private static final String DEFAULT_LANG = "cn";
    private static final String DEFAULT_DEVICE = "PC";

    @Override
    public void process() {
        SessionCreateCfg cfg = this.getCmpData(SessionCreateCfg.class);
        if (cfg == null) {
            throw new ServiceException("SESSION_CREATE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("SESSION_CREATE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("SESSION_CREATE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        if (cfg.getUserName() == null || cfg.getUserName().isBlank()) {
            throw new ServiceException("SESSION_CREATE 组件缺少 userName 配置（tag=" + tag + "）");
        }
        if (cfg.getPassword() == null || cfg.getPassword().isBlank()) {
            throw new ServiceException("SESSION_CREATE 组件缺少 password 配置（tag=" + tag + "）");
        }

        // 参数解析：支持裸路径 / 混合字符串 / 字面量
        String connectionId = resolveStr(cfg.getConnectionId());
        String userName = resolveStr(cfg.getUserName());
        String password = resolveStr(cfg.getPassword());
        String clientIp = cfg.getClientIp() == null || cfg.getClientIp().isBlank()
                ? DEFAULT_CLIENT_IP : resolveStr(cfg.getClientIp());
        String lang = cfg.getLang() == null || cfg.getLang().isBlank()
                ? DEFAULT_LANG : resolveStr(cfg.getLang());
        String device = cfg.getDevice() == null || cfg.getDevice().isBlank()
                ? DEFAULT_DEVICE : resolveStr(cfg.getDevice());

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(connectionId);

        // 从 Connection 配置取 ipWhiteList 塞入请求体（决策 9.1.7）
        BpmHttpConnectionCfg connCfg = connector.fromConnection(conn);
        List<String> ipWhiteList = connCfg.getIpWhiteList() != null
                ? connCfg.getIpWhiteList() : List.of();

        SessionCreateRequest request = new SessionCreateRequest();
        request.setUserName(userName);
        request.setPassword(password);
        request.setClientIp(clientIp);
        request.setLang(lang);
        request.setDevice(device);
        request.setIpWhiteList(ipWhiteList);

        Object result = connector.createSession(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("SESSION_CREATE 响应非 JSON 对象: " + result);
        }
        // 平铺 sessionId / idCard 到数据空间
        resultMap.forEach((key, value) -> save("$." + tag + "." + key, value));
        log.info("[databus] sessionCreate 完成 tag={} sessionId={}", tag, resultMap.get("sessionId"));
    }

    /**
     * 字符串参数解析：纯路径读取 / 混合路径替换 / 字面量原样返回，统一转 String。
     */
    private String resolveStr(Object input) {
        if (input == null) {
            return null;
        }
        Object resolved = resolveParam(input);
        return resolved == null ? null : resolved.toString();
    }
}
