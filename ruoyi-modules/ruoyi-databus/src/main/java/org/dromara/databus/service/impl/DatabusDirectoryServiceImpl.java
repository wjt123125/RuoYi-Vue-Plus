package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusDirectory;
import org.dromara.databus.domain.bo.DatabusDirectoryBo;
import org.dromara.databus.domain.vo.DatabusDirectoryVo;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusDirectoryMapper;
import org.dromara.databus.service.IDatabusDirectoryService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 统一目录 Service 实现。
 * <p>
 * 目录是工作区统一资源树的组织层（parent_id 自引用多级树）：
 * 删除只做拦截不做级联（子目录非空/挂链路/挂组件均拒绝），
 * 拖拽换父做自指与环校验（沿新父向上爬祖先链，遇自身即成环），
 * 链路与组件归属移动改写各自 directory_id（可空=未归组，前端树虚拟节点兜底）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusDirectoryServiceImpl implements IDatabusDirectoryService {

    /**
     * 根目录哨兵值（parent_id=0 表示挂在根下）
     */
    private static final Long ROOT_PARENT = 0L;

    /**
     * 目录层级深限（防环校验向上爬的最大步数，防御性兜底）
     */
    private static final int MAX_DEPTH = 100;

    /**
     * 目录名称最大长度（与 Bo @Size 约束一致）
     */
    private static final int NAME_MAX_LEN = 100;

    private final DatabusDirectoryMapper directoryMapper;

    private final DatabusChainMapper chainMapper;

    private final DatabusComponentMapper componentMapper;

    @Override
    public List<DatabusDirectoryVo> queryList(Long userId) {
        LambdaQueryWrapper<DatabusDirectory> lqw = Wrappers.lambdaQuery();
        // 资源树按人隔离：非管理员只看自己的目录（owner_id 空的历史共享目录仅管理员可见）
        if (userId != null && !LoginHelper.isSuperAdmin(userId)) {
            lqw.eq(DatabusDirectory::getOwnerId, userId);
        }
        lqw.orderByAsc(DatabusDirectory::getSort)
            .orderByAsc(DatabusDirectory::getId);
        return directoryMapper.selectVoList(lqw);
    }

    @Override
    public DatabusDirectoryVo queryById(Long id) {
        return directoryMapper.selectVoById(id);
    }

    @Override
    public Boolean insertByBo(DatabusDirectoryBo bo, Long userId) {
        validateNameNotBlank(bo);
        Long parentId = normalizeParent(bo.getParentId());
        if (!ROOT_PARENT.equals(parentId)) {
            checkParentExists(parentId);
        }
        validateNameUnique(bo.getId(), parentId, bo.getDirectoryName());
        DatabusDirectory add = MapstructUtils.convert(bo, DatabusDirectory.class);
        add.setId(null);
        // parentId 规范化：null → 0（根），保证不为 null 落库
        add.setParentId(parentId);
        // 目录归属当前用户（资源树按人隔离；内部调用无 userId 时留空=管理员视角可见）
        add.setOwnerId(userId);
        if (add.getSort() == null) {
            add.setSort(0);
        }
        boolean flag = directoryMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    @Override
    public Boolean updateByBo(DatabusDirectoryBo bo) {
        DatabusDirectory existing = directoryMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("目录不存在或已删除");
        }
        validateNameNotBlank(bo);
        Long parentId = normalizeParent(bo.getParentId());
        if (!ROOT_PARENT.equals(parentId)) {
            // 自指/环校验只在换父时需要，重命名不换父时跳过（同值也会在环校验中安全通过）
            checkParentExists(parentId);
            checkNoCycle(bo.getId(), parentId);
        }
        validateNameUnique(bo.getId(), parentId, bo.getDirectoryName());
        DatabusDirectory update = MapstructUtils.convert(bo, DatabusDirectory.class);
        // parentId/sort 显式规范化写入：编辑接口的 null 语义是"移到根/排序 0"而非"不更新"
        update.setParentId(parentId);
        // 归属人不可经编辑接口变更（资源树按人隔离的身份列，管理员后台治理）
        update.setOwnerId(null);
        if (update.getSort() == null) {
            update.setSort(0);
        }
        return directoryMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        for (Long id : ids) {
            DatabusDirectory existing = directoryMapper.selectById(id);
            if (existing == null) {
                continue;
            }
            Long childCount = directoryMapper.selectCount(Wrappers.<DatabusDirectory>lambdaQuery()
                .eq(DatabusDirectory::getParentId, id));
            if (childCount != null && childCount > 0) {
                throw new ServiceException("目录下存在子目录，请先移除或转移子目录");
            }
            Long chainCount = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getDirectoryId, id));
            if (chainCount != null && chainCount > 0) {
                throw new ServiceException("目录下存在链路，请先移出链路");
            }
            Long componentCount = componentMapper.selectCount(Wrappers.<DatabusComponent>lambdaQuery()
                .eq(DatabusComponent::getDirectoryId, id));
            if (componentCount != null && componentCount > 0) {
                throw new ServiceException("目录下存在组件，无法删除");
            }
            directoryMapper.deleteById(id);
        }
        return true;
    }

    @Override
    public Boolean moveChain(Long chainId, Long directoryId) {
        DatabusChain chain = chainMapper.selectById(chainId);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (directoryId != null) {
            checkParentExists(directoryId);
        }
        return chainMapper.update(null, Wrappers.<DatabusChain>lambdaUpdate()
            .eq(DatabusChain::getId, chainId)
            // 显式 set null：拖回未归组（directory_id 置空）必须覆盖旧值
            .set(DatabusChain::getDirectoryId, directoryId)) > 0;
    }

    @Override
    public Boolean moveComponent(Long componentId, Long directoryId) {
        DatabusComponent component = componentMapper.selectById(componentId);
        if (component == null) {
            throw new ServiceException("组件不存在或已删除");
        }
        if (directoryId != null) {
            checkParentExists(directoryId);
        }
        return componentMapper.update(null, Wrappers.<DatabusComponent>lambdaUpdate()
            .eq(DatabusComponent::getId, componentId)
            // 显式 set null：拖回未归组（directory_id 置空）必须覆盖旧值
            .set(DatabusComponent::getDirectoryId, directoryId)) > 0;
    }

    /**
     * 目录名称非空/长度手动校验（模块 Controller 惯用裸 @Validated，AddGroup 约束不生效，此处兜底）
     */
    private void validateNameNotBlank(DatabusDirectoryBo bo) {
        if (bo.getDirectoryName() == null || bo.getDirectoryName().isBlank()) {
            throw new ServiceException("目录名称不能为空");
        }
        if (bo.getDirectoryName().length() > NAME_MAX_LEN) {
            throw new ServiceException("目录名称长度不能超过" + NAME_MAX_LEN + "个字符");
        }
    }

    /**
     * parentId 规范化：null → 0（根目录哨兵）
     */
    private Long normalizeParent(Long parentId) {
        return parentId == null ? ROOT_PARENT : parentId;
    }

    /**
     * 父目录存在性校验（parent_id 非 0 时必须指向真实目录）
     */
    private void checkParentExists(Long parentId) {
        DatabusDirectory parent = directoryMapper.selectById(parentId);
        if (parent == null) {
            throw new ServiceException("父目录不存在或已删除");
        }
    }

    /**
     * 换父防环校验：沿新父目录向上爬祖先链，遇到自身即成环（含自指）
     */
    private void checkNoCycle(Long selfId, Long newParentId) {
        if (newParentId.equals(selfId)) {
            throw new ServiceException("不能把目录移动到自身之下");
        }
        Long cursor = newParentId;
        int depth = 0;
        while (cursor != null && !ROOT_PARENT.equals(cursor)) {
            if (cursor.equals(selfId)) {
                throw new ServiceException("不能把目录移动到自己的子目录之下");
            }
            DatabusDirectory node = directoryMapper.selectById(cursor);
            cursor = node == null ? null : node.getParentId();
            if (++depth > MAX_DEPTH) {
                throw new ServiceException("目录层级超过上限，疑似环数据，请检查目录数据");
            }
        }
    }

    /**
     * 同级目录名称唯一校验（同级重名会让树节点失去辨识度）
     */
    private void validateNameUnique(Long selfId, Long parentId, String directoryName) {
        Long count = directoryMapper.selectCount(Wrappers.<DatabusDirectory>lambdaQuery()
            .eq(DatabusDirectory::getParentId, parentId)
            .eq(DatabusDirectory::getDirectoryName, directoryName)
            .ne(selfId != null, DatabusDirectory::getId, selfId));
        if (count != null && count > 0) {
            throw new ServiceException("同级目录已存在同名目录：" + directoryName);
        }
    }

}
