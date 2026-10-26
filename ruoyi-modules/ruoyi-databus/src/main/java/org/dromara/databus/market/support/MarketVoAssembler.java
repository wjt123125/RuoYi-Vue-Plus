package org.dromara.databus.market.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.vo.DatabusMarketItemVo;
import org.dromara.databus.domain.vo.DatabusMarketReviewDetailVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.enums.ExpressParserEnum;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 市场相关的对象转换：市场物料行 → 列表和详情用的视图对象，画布节点树 → 预览用的节点列表。
 * <p>
 * 这两件事都不是某一个类独占的：物料视图，查询那边的分页列表、分类详情、我的上架、待审核列表要用，
 * 上架那边的审核详情也要用；预览节点列表，查询那边的「安装前看实现」要用，上架那边的审核预览也要用。
 * 拆类之后如果两边各留一份，改了一边忘了另一边，两边给出的结果就会不一样，所以集中放在这里。
 *
 * @author databus
 * @since 2026-10-26
 */
@RequiredArgsConstructor
@Component
public class MarketVoAssembler {

    private final DatabusComponentMapper componentMapper;

    /**
     * 市场物料行 → 视图对象（不含「装了没有」这类跟当前用户有关的字段，那些由调用方另填）。
     */
    public DatabusMarketItemVo toItemVo(DatabusMarketItem item) {
        DatabusMarketItemVo vo = new DatabusMarketItemVo();
        vo.setId(item.getId());
        vo.setOwnerId(item.getOwnerId());
        vo.setOwnerCode(item.getOwnerCode());
        vo.setResourceType(item.getResourceType());
        vo.setSourceId(item.getSourceId());
        vo.setCodeDisplay(item.getCodeDisplay());
        vo.setName(item.getName());
        vo.setSummary(item.getSummary());
        vo.setIcon(item.getIcon());
        vo.setColor(item.getColor());
        vo.setCategoryId(item.getCategoryId());
        vo.setTags(item.getTags());
        vo.setStatus(item.getStatus());
        vo.setInstallCount(item.getInstallCount());
        vo.setRefCount(item.getRefCount());
        vo.setForkPublishCount(item.getForkPublishCount());
        vo.setCreateTime(item.getCreateTime());
        vo.setUpdateTime(item.getUpdateTime());
        vo.setRemark(item.getRemark());
        vo.setModifyNote(item.getModifyNote());
        vo.setReviewBy(item.getReviewBy());
        vo.setReviewTime(item.getReviewTime());
        return vo;
    }

    /**
     * 一层层往下收集画布上的节点，拼成预览用的列表（审核预览和「安装前看实现」都用它）：
     * EL 关键字那种结构节点（ExpressParserEnum.type，不分大小写）只记节点本身、不记编码；
     * 业务组件节点记 type、编码、名称。condition 分支和所有 children 都要走一遍，
     * 走法和 ChainForkRedirector.collectComponentCodes 一样（两边规则必须一致，
     * 不然同一个链在两个地方看到的组件清单会对不上）。
     * <p>
     * depth 一层加一（根节点是 0，children 和 condition 里的加 1），
     * 前端按这个层级缩进着画成树形，不缩进的话就是一长条排下来、看不出层次。
     */
    public void collectPreviewNodes(CmpProperty node, List<DatabusMarketReviewDetailVo.NodeVo> nodes, int depth) {
        if (node == null) {
            return;
        }
        DatabusMarketReviewDetailVo.NodeVo vo = new DatabusMarketReviewDetailVo.NodeVo();
        vo.setType(node.getType());
        vo.setDepth(depth);
        boolean structure = node.getType() == null
            || Arrays.stream(ExpressParserEnum.values())
            .map(ExpressParserEnum::getType)
            .anyMatch(t -> t.equalsIgnoreCase(node.getType()));
        vo.setStructure(structure);
        if (!structure) {
            String code = StringUtils.isNotBlank(node.getId()) ? node.getId()
                : (node.getProperties() != null ? node.getProperties().getId() : null);
            vo.setCode(code);
            if (StringUtils.isNotBlank(code)) {
                // 用组件行里的名字来显示（组件行查不到就是已经被删了，这时只能拿编码当名字显示）
                DatabusComponent component = componentMapper.selectOne(Wrappers.<DatabusComponent>lambdaQuery()
                    .eq(DatabusComponent::getComponentCode, code)
                    .last("limit 1"));
                vo.setName(component == null ? code : component.getComponentName());
            }
        }
        nodes.add(vo);
        collectPreviewNodes(node.getCondition(), nodes, depth + 1);
        if (node.getChildren() != null) {
            for (CmpProperty child : node.getChildren()) {
                collectPreviewNodes(child, nodes, depth + 1);
            }
        }
    }
}
