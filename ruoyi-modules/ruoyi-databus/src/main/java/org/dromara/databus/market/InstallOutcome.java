package org.dromara.databus.market;

import org.dromara.databus.domain.vo.DatabusMarketInstallVo;

import java.util.List;

/**
 * install 各分支的产物：链路复制副本和组件共用同一个源，两条路要写库的字段一次性都放在这里，
 * {@code saveInstallRecord} 和 {@code toInstallVo} 共用（连接器不写安装记录，不走这里）。
 * <p>
 * 只供 {@link MarketInstallService}、{@link MarketChainCopySupport}、{@link MarketInstallQuerySupport}
 * 在同包内传递安装结果，不对外暴露。
 *
 * @param localResourceId   本地资源 id——链路＝主链副本的行 id，组件＝源组件 id
 * @param localResourceType 本地资源类型（多态引用判别列，与 localResourceId 一起标识本地资源）
 * @param mode             FORK / SHARED
 * @param contentHash      安装时按源内容算出的一个标识值，卸载和改版时用它比对内容有没有变
 * @param directoryId      安装记录行放在哪个目录，只有组件安装填（链路的目录记在副本行上）
 * @param componentCode    组件编码，只有装组件时填
 * @param componentEnabled 源组件现在是不是启用状态，只有装组件时填
 * @param cascade          链路跟着复制出来的清单，只有装链路时填
 */
record InstallOutcome(Long localResourceId, String localResourceType, String mode, String contentHash,
                      Long directoryId, String componentCode, Boolean componentEnabled,
                      List<DatabusMarketInstallVo.CascadeItemVo> cascade) {
}
