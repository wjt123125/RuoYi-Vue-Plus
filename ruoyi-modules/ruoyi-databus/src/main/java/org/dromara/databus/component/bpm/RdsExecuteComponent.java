package org.dromara.databus.component.bpm;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.databus.component.DatabusNodeComponent;
import org.dromara.databus.connector.Connection;
import org.dromara.databus.connector.bpm.BpmHttpConnector;
import org.dromara.databus.connector.bpm.dto.RdsExecuteRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RDS_EXECUTE 组件（注册名 {@code rdsExecute}）。
 * <p>
 * 通过 BPM connector 执行 BPM 后台注册的 RDS 数据源 SQL，对应老系统 RdsConfigProcessor
 * （SqlValueProcessor 的规则引擎 @sqlValue 标量取数并入本组件标量方法）。
 * 支持 getString/getInt/getLong/getDouble/getMap/getMaps/update/batch 八种方法，
 * batch 支持多 SQL 无参与单 SQL 批量参数两种模式。
 *
 * <p>args 参数逐元素走统一 resolveParam（裸路径/混合模板/常量），SQL 文本原样透传。
 * 响应存 {@code $.<tag>.method} 与 {@code $.<tag>.data}。
 *
 * @author databus
 */
@Slf4j
@LiteflowComponent("rdsExecute")
public class RdsExecuteComponent extends DatabusNodeComponent {

    private static final String DEFAULT_METHOD = "getMaps";

    @Override
    public void process() {
        RdsExecuteCfg cfg = this.getCmpData(RdsExecuteCfg.class);
        if (cfg == null) {
            throw new ServiceException("RDS_EXECUTE 组件缺少参数配置（tag=" + this.getTag() + "）");
        }
        String tag = this.getTag();
        if (tag == null || tag.isBlank()) {
            throw new ServiceException("RDS_EXECUTE 组件缺少数据空间标识（tag）");
        }
        if (cfg.getConnectionId() == null || cfg.getConnectionId().isBlank()) {
            throw new ServiceException("RDS_EXECUTE 组件缺少 connectionId 配置（tag=" + tag + "）");
        }
        String rdsId = resolveStr(cfg.getRdsId());
        if (rdsId == null || rdsId.isBlank()) {
            throw new ServiceException("RDS_EXECUTE 组件缺少 rdsId 配置（tag=" + tag + "）");
        }
        if (cfg.getSql() == null) {
            throw new ServiceException("RDS_EXECUTE 组件缺少 sql 配置（tag=" + tag + "）");
        }
        String method = cfg.getMethod() == null || cfg.getMethod().isBlank()
                ? DEFAULT_METHOD : resolveStr(cfg.getMethod());

        // 组装请求：SQL 原样透传；args 逐元素参数解析（batch 嵌套数组递归解析）
        RdsExecuteRequest request = new RdsExecuteRequest();
        request.setRdsId(rdsId);
        request.setMethod(method);
        request.setSql(cfg.getSql());
        request.setArgs(resolveArgs(cfg.getArgs()));
        request.setFetchSize(cfg.getFetchSize());
        request.setMaxRows(cfg.getMaxRows());

        BpmHttpConnector connector = SpringUtils.getBean(BpmHttpConnector.class);
        Connection conn = getDatabusContext().getConnection(resolveStr(cfg.getConnectionId()));

        Object result = connector.rdsExecute(conn, request);
        if (!(result instanceof Map<?, ?> resultMap)) {
            throw new ServiceException("RDS_EXECUTE 响应非 JSON 对象: " + result);
        }
        Object methodEcho = resultMap.get("method");
        if (methodEcho == null) {
            throw new ServiceException("RDS_EXECUTE 响应缺少 method 字段: " + result);
        }
        save("$." + tag + ".method", methodEcho);
        save("$." + tag + ".data", resultMap.get("data"));
        log.info("[databus] rdsExecute 完成 tag={} rdsId={} method={}", tag, rdsId, methodEcho);
    }

    /**
     * args 递归解析：List 逐元素递归（batch 批量参数是数组的数组），
     * 叶子走 resolveParam（裸路径/混合模板/常量；数字布尔原样返回）。
     */
    private Object resolveArgs(Object args) {
        if (args == null) {
            return null;
        }
        if (args instanceof List<?> list) {
            List<Object> resolved = new ArrayList<>(list.size());
            for (Object item : list) {
                resolved.add(resolveArgs(item));
            }
            return resolved;
        }
        return resolveParam(args);
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
