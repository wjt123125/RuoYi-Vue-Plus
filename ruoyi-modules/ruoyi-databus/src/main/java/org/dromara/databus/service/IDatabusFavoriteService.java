package org.dromara.databus.service;

import org.dromara.databus.domain.vo.DatabusFavoriteVo;

import java.util.List;

/**
 * 用户收藏 Service 接口（工作区统一资源树「我的收藏」）
 *
 * @author databus
 */
public interface IDatabusFavoriteService {

    /**
     * 查询当前用户收藏列表（回查资源名/类型/图标）
     */
    List<DatabusFavoriteVo> queryList(Long userId);

    /**
     * 新增收藏（幂等：已存在直接返回成功）
     */
    Boolean add(Long userId, String resourceType, Long resourceId);

    /**
     * 取消收藏（幂等：不存在也返回成功）
     */
    Boolean remove(Long userId, String resourceType, Long resourceId);

}
