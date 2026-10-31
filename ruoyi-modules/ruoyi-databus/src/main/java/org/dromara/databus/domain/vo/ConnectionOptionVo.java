package org.dromara.databus.domain.vo;

/**
 * 连接轻量选项（/databus/connection/options）：编辑器 ConnectionSelect 控件用。
 * <p>
 * 不分页、不含 endpoint/密钥等敏感配置；字段名 connectorType 与连接管理域口径一致
 * （SysDatabusConnectionVo 同源，设计稿 §5.3 写作 connType，落地统一为 connectorType）。
 *
 * @param id               连接主键
 * @param connectionId     连接业务键（组件 Cfg.connectionId 引用的就是它，如 bpm-default）
 * @param connectionName   连接名称
 * @param connectorType    Connector 类型标识（如 bpmHttp、jdbc-postgresql）
 * @param connectorFamily  连接器能力族（{@code ConnectorDescriptor.family}，如 jdbc / bpmHttp）；
 *                         组件侧 {@code @DatabusProp(connectorFamily=...)} 按族过滤下拉，
 *                         故本字段是「dbQuery 只看见 SQL 连接器」的唯一依据（方案 §8.3）。
 *                         连接器未装载或 describe() 失败时为 null——此时前端**不过滤**，
 *                         宁可多列一个不可用的连接，也不能让老链的既有值凭空消失。
 * @author databus
 */
public record ConnectionOptionVo(Long id, String connectionId, String connectionName, String connectorType,
                                 String connectorFamily) {
}
