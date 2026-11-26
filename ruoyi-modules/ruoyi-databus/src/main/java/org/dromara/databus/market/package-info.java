/**
 * 物料市场域服务：查询、分类、安装、发布。
 * <p>
 * 本包放「域服务」——无接口、由 {@link org.dromara.databus.service.impl.DatabusMarketServiceImpl}
 * 门面转发的入口类，命名沿用 {@code *Service}；
 * 内部协作者在 {@link org.dromara.databus.market.support}，
 * 内部数据载体在 {@link org.dromara.databus.market.model}。
 *
 * @author databus
 */
package org.dromara.databus.market;