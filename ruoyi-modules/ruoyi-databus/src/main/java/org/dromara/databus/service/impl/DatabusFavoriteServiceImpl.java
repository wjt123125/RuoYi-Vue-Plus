package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusUserFavorite;
import org.dromara.databus.domain.vo.DatabusFavoriteVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusUserFavoriteMapper;
import org.dromara.databus.service.IDatabusFavoriteService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户收藏 Service 实现。
 * <p>
 * 收藏是工作区统一资源树「我的收藏」section 的数据源：按当前登录用户平铺列出其收藏的
 * 链路/组件，并回查资源名/编码/图标供前端渲染。目录是团队共享的归属单真相，
 * 收藏是唯一的个人化组织维度（JetBrains Favorites 范式）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusFavoriteServiceImpl implements IDatabusFavoriteService {

    /**
     * 资源类型：链路
     */
    public static final String TYPE_CHAIN = "chain";

    /**
     * 资源类型：组件
     */
    public static final String TYPE_COMPONENT = "component";

    private final DatabusUserFavoriteMapper favoriteMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    @Override
    public List<DatabusFavoriteVo> queryList(Long userId) {
        List<DatabusUserFavorite> rows = favoriteMapper.selectList(Wrappers.<DatabusUserFavorite>lambdaQuery()
            .eq(DatabusUserFavorite::getUserId, userId)
            .orderByDesc(DatabusUserFavorite::getCreateTime)
            .orderByDesc(DatabusUserFavorite::getId));
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> chainIds = rows.stream()
            .filter(row -> TYPE_CHAIN.equals(row.getResourceType()))
            .map(DatabusUserFavorite::getResourceId)
            .toList();
        List<Long> componentIds = rows.stream()
            .filter(row -> TYPE_COMPONENT.equals(row.getResourceType()))
            .map(DatabusUserFavorite::getResourceId)
            .toList();

        Map<Long, DatabusChain> chainMap = chainIds.isEmpty() ? Map.of()
            : chainMapper.selectByIds(chainIds).stream()
                .collect(Collectors.toMap(DatabusChain::getId, chain -> chain, (a, b) -> a));
        Map<Long, DatabusComponent> componentMap = componentIds.isEmpty() ? Map.of()
            : componentMapper.selectByIds(componentIds).stream()
                .collect(Collectors.toMap(DatabusComponent::getId, component -> component, (a, b) -> a));

        List<DatabusFavoriteVo> result = new ArrayList<>(rows.size());
        for (DatabusUserFavorite row : rows) {
            DatabusFavoriteVo vo = new DatabusFavoriteVo();
            vo.setResourceType(row.getResourceType());
            vo.setResourceId(row.getResourceId());
            if (TYPE_CHAIN.equals(row.getResourceType())) {
                DatabusChain chain = chainMap.get(row.getResourceId());
                if (chain != null) {
                    vo.setResourceName(chain.getChainName());
                    vo.setResourceCode(chain.getChainCode());
                }
            } else if (TYPE_COMPONENT.equals(row.getResourceType())) {
                DatabusComponent component = componentMap.get(row.getResourceId());
                if (component != null) {
                    vo.setResourceName(component.getComponentName());
                    vo.setResourceCode(component.getComponentCode());
                    vo.setIcon(component.getIcon());
                }
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public Boolean add(Long userId, String resourceType, Long resourceId) {
        validateResourceType(resourceType);
        Long exists = favoriteMapper.selectCount(Wrappers.<DatabusUserFavorite>lambdaQuery()
            .eq(DatabusUserFavorite::getUserId, userId)
            .eq(DatabusUserFavorite::getResourceType, resourceType)
            .eq(DatabusUserFavorite::getResourceId, resourceId));
        if (exists != null && exists > 0) {
            // 幂等：重复收藏直接视为成功（唯一键 uk_favorite 亦兜底）
            return true;
        }
        DatabusUserFavorite favorite = new DatabusUserFavorite();
        favorite.setUserId(userId);
        favorite.setResourceType(resourceType);
        favorite.setResourceId(resourceId);
        favorite.setCreateTime(LocalDateTime.now());
        return favoriteMapper.insert(favorite) > 0;
    }

    @Override
    public Boolean remove(Long userId, String resourceType, Long resourceId) {
        validateResourceType(resourceType);
        favoriteMapper.delete(Wrappers.<DatabusUserFavorite>lambdaQuery()
            .eq(DatabusUserFavorite::getUserId, userId)
            .eq(DatabusUserFavorite::getResourceType, resourceType)
            .eq(DatabusUserFavorite::getResourceId, resourceId));
        // 幂等：不存在也视为取消成功
        return true;
    }

    /**
     * 资源类型白名单校验（chain / component）
     */
    private void validateResourceType(String resourceType) {
        if (!TYPE_CHAIN.equals(resourceType) && !TYPE_COMPONENT.equals(resourceType)) {
            throw new ServiceException("不支持资源类型：" + resourceType);
        }
    }

}
