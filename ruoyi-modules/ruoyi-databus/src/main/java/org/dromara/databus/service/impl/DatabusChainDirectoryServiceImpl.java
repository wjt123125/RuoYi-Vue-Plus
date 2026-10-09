package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusChainDirectory;
import org.dromara.databus.domain.bo.DatabusChainDirectoryBo;
import org.dromara.databus.domain.vo.DatabusChainDirectoryVo;
import org.dromara.databus.mapper.DatabusChainDirectoryMapper;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.service.IDatabusChainDirectoryService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 链路目录 Service 实现。
 * <p>
 * 目录是链路工作台资源树的组织层（parent_id 自引用多级树）：
 * 删除只做拦截不做级联（子目录非空/挂链非空均拒绝），
 * 拖拽换父做自指与环校验（沿新父向上爬祖先链，遇自身即成环），
 * 链路归属移动改写 databus_chain.directory_id（可空=未归组，前端树虚拟节点兜底）。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusChainDirectoryServiceImpl implements IDatabusChainDirectoryService {

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

    private final DatabusChainDirectoryMapper directoryMapper;

    private final DatabusChainMapper chainMapper;

    @Override
    public List<DatabusChainDirectoryVo> queryList() {
        LambdaQueryWrapper<DatabusChainDirectory> lqw = Wrappers.lambdaQuery();
        lqw.orderByAsc(DatabusChainDirectory::getSort)
            .orderByAsc(DatabusChainDirectory::getId);
        return directoryMapper.selectVoList(lqw);
    }

    @Override
    public DatabusChainDirectoryVo queryById(Long id) {
        return directoryMapper.selectVoById(id);
    }

    @Override
    public Boolean insertByBo(DatabusChainDirectoryBo bo) {
        validateNameNotBlank(bo);
        Long parentId = normalizeParent(bo.getParentId());
        if (!ROOT_PARENT.equals(parentId)) {
            checkParentExists(parentId);
        }
        validateNameUnique(bo.getId(), parentId, bo.getDirectoryName());
        DatabusChainDirectory add = MapstructUtils.convert(bo, DatabusChainDirectory.class);
        add.setId(null);
        // parentId 规范化：null → 0（根），保证不为 null 落库
        add.setParentId(parentId);
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
    public Boolean updateByBo(DatabusChainDirectoryBo bo) {
        DatabusChainDirectory existing = directoryMapper.selectById(bo.getId());
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
        DatabusChainDirectory update = MapstructUtils.convert(bo, DatabusChainDirectory.class);
        // parentId/sort 显式规范化写入：编辑接口的 null 语义是"移到根/排序 0"而非"不更新"
        update.setParentId(parentId);
        if (update.getSort() == null) {
            update.setSort(0);
        }
        return directoryMapper.updateById(update) > 0;
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        for (Long id : ids) {
            DatabusChainDirectory existing = directoryMapper.selectById(id);
            if (existing == null) {
                continue;
            }
            Long childCount = directoryMapper.selectCount(Wrappers.<DatabusChainDirectory>lambdaQuery()
                .eq(DatabusChainDirectory::getParentId, id));
            if (childCount != null && childCount > 0) {
                throw new ServiceException("目录下存在子目录，请先移除或转移子目录");
            }
            Long chainCount = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
                .eq(DatabusChain::getDirectoryId, id));
            if (chainCount != null && chainCount > 0) {
                throw new ServiceException("目录下存在链路，请先移出链路");
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

    /**
     * 目录名称非空/长度手动校验（模块 Controller 惯用裸 @Validated，AddGroup 约束不生效，此处兜底）
     */
    private void validateNameNotBlank(DatabusChainDirectoryBo bo) {
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
        DatabusChainDirectory parent = directoryMapper.selectById(parentId);
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
            DatabusChainDirectory node = directoryMapper.selectById(cursor);
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
        Long count = directoryMapper.selectCount(Wrappers.<DatabusChainDirectory>lambdaQuery()
            .eq(DatabusChainDirectory::getParentId, parentId)
            .eq(DatabusChainDirectory::getDirectoryName, directoryName)
            .ne(selfId != null, DatabusChainDirectory::getId, selfId));
        if (count != null && count > 0) {
            throw new ServiceException("同级目录已存在同名目录：" + directoryName);
        }
    }

}
