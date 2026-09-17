/**
 * BPM HTTP 连接器 4 个操作入参 DTO 的包说明。
 *
 * <p>这些 DTO 是 BPM 端 {@code com.awspaas.databus.connector.dto} 的字段副本，
 * 因 BPM 端是独立仓库（不依赖 ruoyi-databus，也不被 ruoyi-databus 依赖），
 * 故在 ruoyi-databus 侧重定义相同字段结构，由 {@link org.dromara.databus.connector.bpm.BpmHttpConnector}
 * 序列化后通过 HTTP 传给 BPM 端，BPM 端 fastjson 反序列化为自己的 DTO，字段名匹配即可。
 *
 * <p>字段命名与 BPM 端完全一致（如 userName / ipWhiteList / boList），保证 JSON 序列化后的 key 相同。
 *
 * @author databus
 */
package org.dromara.databus.connector.bpm.dto;
