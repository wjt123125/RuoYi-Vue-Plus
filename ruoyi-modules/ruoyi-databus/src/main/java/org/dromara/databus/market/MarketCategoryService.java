package org.dromara.databus.market;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusMarketCategory;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.MarketCategoryBo;
import org.dromara.databus.domain.bo.MarketCategoryMergeBo;
import org.dromara.databus.mapper.DatabusMarketCategoryMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.dromara.databus.market.support.MarketResourceTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 市场分类治理（新增 / 改名 / 排序 / 删除 / 合并）。
 * <p>
 * 分类是货架目录，只做治理动作：有物料的分类不给裸删，只能「合并到…」——
 * 一个事务里把物料整批迁到目标分类（目标为空＝未分类），再删源分类。
 * 空分类才允许直接删除。
 *
 * @author databus
 */
@Component
@RequiredArgsConstructor
public class MarketCategoryService {

    /**
     * 分类允许的适用类型（空串＝通用）
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
        "", MarketResourceTypes.COMPONENT, MarketResourceTypes.CHAIN, MarketResourceTypes.CONNECTOR);

    private final DatabusMarketCategoryMapper categoryMapper;

    private final DatabusMarketItemMapper itemMapper;

    /**
     * 新增分类
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(MarketCategoryBo bo) {
        DatabusMarketCategory category = new DatabusMarketCategory();
        category.setName(bo.getName().trim());
        category.setResourceType(normalizeType(bo.getResourceType()));
        category.setSort(bo.getSort() == null ? 0 : bo.getSort());
        category.setParentId(0L);
        category.setDefaultTags(normalizeTags(bo.getDefaultTags()));
        categoryMapper.insert(category);
        return category.getId();
    }

    /**
     * 改名 / 排序 / 推荐标签（适用类型不改，避免与已挂物料的类型不一致）
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean update(Long id, MarketCategoryBo bo) {
        requireCategory(id);
        DatabusMarketCategory category = new DatabusMarketCategory();
        category.setId(id);
        category.setName(bo.getName().trim());
        category.setSort(bo.getSort() == null ? 0 : bo.getSort());
        category.setDefaultTags(normalizeTags(bo.getDefaultTags()));
        return categoryMapper.updateById(category) > 0;
    }

    /**
     * 删除分类：仅限没有物料挂在它下面的分类
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean delete(Long id) {
        requireCategory(id);
        long count = countItems(id);
        if (count > 0) {
            throw new ServiceException("该分类下还有 " + count + " 个物料，请先「合并到」其它分类");
        }
        return categoryMapper.deleteById(id) > 0;
    }

    /**
     * 合并：源分类下的物料整批迁移到目标分类（目标为空＝未分类），随后删源分类
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean merge(Long id, MarketCategoryMergeBo bo) {
        DatabusMarketCategory source = requireCategory(id);
        Long targetId = bo == null ? null : bo.getTargetCategoryId();
        if (targetId != null) {
            if (targetId.equals(id)) {
                throw new ServiceException("不能合并到分类自身");
            }
            DatabusMarketCategory target = requireCategory(targetId);
            // 类型要兼容：目标为通用，或与源分类同一适用类型
            String sourceType = StringUtils.defaultString(source.getResourceType());
            String targetType = StringUtils.defaultString(target.getResourceType());
            if (!targetType.isEmpty() && !targetType.equals(sourceType)) {
                throw new ServiceException("目标分类的适用类型与源分类不一致");
            }
        }
        itemMapper.update(null, Wrappers.<DatabusMarketItem>lambdaUpdate()
            .eq(DatabusMarketItem::getCategoryId, id)
            .set(DatabusMarketItem::getCategoryId, targetId));
        categoryMapper.deleteById(id);
        return true;
    }

    /**
     * 分类下物料数（不限在线状态——下线物料同样挂着归属）
     */
    private long countItems(Long categoryId) {
        return itemMapper.selectCount(Wrappers.<DatabusMarketItem>lambdaQuery()
            .eq(DatabusMarketItem::getCategoryId, categoryId));
    }

    private DatabusMarketCategory requireCategory(Long id) {
        DatabusMarketCategory category = id == null ? null : categoryMapper.selectById(id);
        if (category == null) {
            throw new ServiceException("分类不存在或已删除");
        }
        return category;
    }

    private String normalizeType(String raw) {
        String type = StringUtils.defaultString(raw).trim().toUpperCase();
        if (!ALLOWED_TYPES.contains(type)) {
            throw new ServiceException("分类适用类型不合法");
        }
        return type;
    }

    /**
     * 推荐标签清洗：去空白、去重，单条限 30 字、最多 20 条，
     * 并按序列化后长度收敛到 default_tags varchar(500) 以内（防止写库截断）。
     */
    private List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = raw.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .map(tag -> tag.length() > 30 ? tag.substring(0, 30) : tag)
            .distinct()
            .limit(20)
            .toList();
        List<String> result = new ArrayList<>();
        // "[]" 占 2 个字符，每个元素额外按引号与逗号预留 3 个字符
        int size = 2;
        for (String tag : cleaned) {
            int add = tag.length() + 3;
            if (size + add > 500) {
                break;
            }
            result.add(tag);
            size += add;
        }
        return result;
    }

}
