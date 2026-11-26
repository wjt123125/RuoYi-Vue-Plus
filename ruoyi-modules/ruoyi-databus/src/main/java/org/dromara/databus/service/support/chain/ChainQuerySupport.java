package org.dromara.databus.service.support.chain;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.vo.ChainStatsVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.springframework.stereotype.Component;

import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_NO;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;

/**
 * 链路定义的读接口：单条加载、列表分页、状态计数。
 * <p>
 * 只被 {@link DatabusChainServiceImpl} 调用，纯读不落库。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ChainQuerySupport {

    private final DatabusChainMapper chainMapper;

    /**
     * 单条加载：编辑器走这里，必须是全量字段（含 canvas_data / el_expression）。
     *
     * @param id 链路主键
     * @return 链路视图对象
     */
    public DatabusChainVo queryById(Long id) {
        return chainMapper.selectVoById(id);
    }

    /**
     * 列表分页。
     *
     * @param bo        查询条件
     * @param pageQuery 分页参数
     * @param userId    当前登录用户（onlyMine 过滤用，超级管理员豁免）
     * @return 分页结果
     */
    public PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery, Long userId) {
        LambdaQueryWrapper<DatabusChain> lqw = Wrappers.lambdaQuery();
        // 列表白名单：排除 canvas_data / el_expression 两个大字段（列表零消费），
        // 保留 cmp_property 供卡片迷你拓扑预览递归，模板三字段供模板卡角标/说明/排序。
        // queryById 保持全量（编辑器加载走该接口）。
        lqw.select(DatabusChain::getId, DatabusChain::getChainCode, DatabusChain::getChainName,
            DatabusChain::getVersion, DatabusChain::getStatus, DatabusChain::getCmpProperty,
            DatabusChain::getDirectoryId,
            DatabusChain::getLogLevel, DatabusChain::getRemark,
            DatabusChain::getIsTemplate, DatabusChain::getTemplateDesc, DatabusChain::getTemplateSort,
            DatabusChain::getCreateTime, DatabusChain::getUpdateTime);
        lqw.like(StringUtils.isNotBlank(bo.getChainCode()), DatabusChain::getChainCode, bo.getChainCode());
        lqw.like(StringUtils.isNotBlank(bo.getChainName()), DatabusChain::getChainName, bo.getChainName());
        if (StringUtils.isNotBlank(bo.getStatus())) {
            lqw.eq(DatabusChain::getStatus, bo.getStatus());
        }
        // 目录归属过滤（链路工作台树按目录拉链用；null 不追加条件）
        lqw.eq(bo.getDirectoryId() != null, DatabusChain::getDirectoryId, bo.getDirectoryId());
        // 资源树按人隔离：onlyMine 只看自己创建的链路（超级管理员豁免，见 admin 视角全量）
        if (Boolean.TRUE.equals(bo.getOnlyMine()) && !LoginHelper.isSuperAdmin(userId)) {
            lqw.eq(DatabusChain::getCreateBy, userId);
        }
        // 双 tab 分流：'1' 精选模板库，'0' 我的链路（排除模板）；null 不追加条件，兼容内部调用
        boolean templateTab = TEMPLATE_FLAG_YES.equals(bo.getIsTemplate());
        if (StringUtils.isNotBlank(bo.getIsTemplate())) {
            lqw.eq(DatabusChain::getIsTemplate, bo.getIsTemplate());
        }
        if (templateTab) {
            // 模板库：手动排序升序，同序按最近更新
            lqw.orderByAsc(DatabusChain::getTemplateSort)
                .orderByDesc(DatabusChain::getUpdateTime)
                .orderByDesc(DatabusChain::getId);
        } else {
            lqw.orderByDesc(DatabusChain::getUpdateTime)
                .orderByDesc(DatabusChain::getId);
        }
        Page<DatabusChainVo> result = chainMapper.selectVoPage(pageQuery.build(), lqw);
        return PageResult.build(result.getRecords(), result.getTotal());
    }

    /**
     * 按 status 分组计数。
     *
     * @return 总数 / 草稿 / 已发布 / 已下线 计数
     */
    public ChainStatsVo countByStatus() {
        long draft = countByStatus(ChainStatusEnum.DRAFT.getCode());
        long published = countByStatus(ChainStatusEnum.ENABLED.getCode());
        long offline = countByStatus(ChainStatusEnum.DISABLED.getCode());
        ChainStatsVo vo = new ChainStatsVo();
        vo.setDraft(draft);
        vo.setPublished(published);
        vo.setOffline(offline);
        // 模板是样板不是生产资产，不计入任何状态计数与总数
        vo.setTotal(draft + published + offline);
        return vo;
    }

    /**
     * 按状态计数（delFlag 由 @TableLogic 自动过滤；精选模板恒为草稿但不计入业务计数）
     */
    private long countByStatus(String status) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getStatus, status)
            .eq(DatabusChain::getIsTemplate, TEMPLATE_FLAG_NO));
        return count == null ? 0L : count;
    }

}
