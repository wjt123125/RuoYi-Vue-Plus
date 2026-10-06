/**
 * BPM 业务组件（已脚本化）。
 *
 * <p>原 12 个 BPM 节点组件（sessionCreate/processStart/boCreate/boQuery/boUpdate/boDelete/
 * processTerminate/taskComplete/rdsExecute/idCardToUserId/fileUpload/fileDownload）
 * 已于 2026-10-06 迁入 databus_component 脚本工件（包 org.dromara.databus.script），
 * 由脚本宿主启动注册、Web 端可改可回滚；本包不再放组件类。
 *
 * <p>BPM 协议层仍在 {@link org.dromara.databus.connector.bpm.BpmHttpConnector}
 * （/portal/openapi 签名网关），脚本组件经 SpringUtils.getBean 静态查找调用。
 *
 * @author databus
 */
package org.dromara.databus.component.bpm;
