package org.dromara.databus.domain.vo;

/**
 * 连接轻量选项（/databus/connection/options）：编辑器 ConnectionSelect 控件用。
 * <p>
 * 不分页、不含 endpoint/密钥等敏感配置；字段名 connectorType 与连接管理域口径一致
 * （SysDatabusConnectionVo 同源，设计稿 §5.3 写作 connType，落地统一为 connectorType）。
 *
 * @param id             连接主键
 * @param connectionId   连接业务键（组件 Cfg.connectionId 引用的就是它，如 bpm-default）
 * @param connectionName 连接名称
 * @param connectorType  Connector 类型标识（如 bpmHttp）
 * @author databus
 */
public record ConnectionOptionVo(Long id, String connectionId, String connectionName, String connectorType) {
}
