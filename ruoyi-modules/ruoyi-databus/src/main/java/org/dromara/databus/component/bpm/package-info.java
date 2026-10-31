/**
 * BPM 业务组件（已脚本化）。
 *
 * <p>原 12 个 BPM 节点组件（sessionCreate/processStart/boCreate/boQuery/boUpdate/boDelete/
 * processTerminate/taskComplete/rdsExecute/idCardToUserId/fileUpload/fileDownload）
 * 已于 2026-10-06 迁入 databus_component 脚本工件（包 org.dromara.databus.script），
 * 由脚本宿主启动注册、Web 端可改可回滚；本包不再放组件类。
 *
 * <p>BPM 协议层仍在 {@code BpmHttpConnector}（/portal/openapi 签名网关），
 * 具体连接器随物料包由插件类加载器在线装载，脚本组件不再静态依赖；
 * 脚本经 {@link org.dromara.databus.connector.ConnectorRegistry#ofType}
 * 按连接类型取到通用 {@link org.dromara.databus.connector.Connector}，
 * 再统一调用 {@code invoke(Map)} 完成业务操作。
 *
 * @author databus
 */
package org.dromara.databus.component.bpm;
