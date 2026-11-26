package org.dromara.databus.service.support.chain;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.context.InputParamValidator;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.DatabusInstall;
import org.dromara.databus.domain.DatabusMarketItem;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.el.bean.ELInfo;
import org.dromara.databus.el.parser.generator.ExpressGenerator;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.LogLevelEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.dromara.databus.mapper.DatabusInstallMapper;
import org.dromara.databus.mapper.DatabusMarketItemMapper;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static org.dromara.databus.constant.ChainConstants.DRAFT_VERSION;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_NO;
import static org.dromara.databus.constant.MarketConstants.TYPE_CHAIN;

/**
 * 链路定义的写接口：新增/编辑草稿、批量删除，以及三者共用的编码唯一性、日志档位与 EL 生成。
 * <p>
 * 草稿不推 Rule-DB；删除时按 chainCode 清理残留规则并级联清安装血缘。
 * 只被 {@link DatabusChainServiceImpl}、{@link ChainCopySupport} 调用。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ChainWriteSupport {

    private final DatabusChainMapper chainMapper;

    private final ExpressGenerator expressGenerator;

    private final ChainRuleDbPublisher ChainRuleDbPublisher;

    /**
     * 安装血缘查询：FORK 副本被直接删除时级联清安装记录，
     * 防「已安装」列表留悬空卡片（打开是空画布）
     */
    private final DatabusInstallMapper installMapper;

    private final DatabusMarketItemMapper marketItemMapper;

    private final ChainRefGraphSupport graphSupport;

    /**
     * 新增链路草稿：EL 由组件树生成，version=1/status=草稿。
     *
     * @param bo 链路业务对象
     * @return 是否新增成功
     */
    public Boolean insert(DatabusChainBo bo) {
        validateChainCodeUnique(bo);
        validateLogLevel(bo.getLogLevel());
        InputParamValidator.validateDefs(bo.getInputParams());
        DatabusChain add = MapstructUtils.convert(bo, DatabusChain.class);
        add.setId(null);
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
        // 新建即普通链路：模板标记只能经模板专用端点打在已有链路上
        add.setIsTemplate(TEMPLATE_FLAG_NO);
        add.setOwnerType(OwnerTypeEnum.USER.getCode());
        add.setElExpression(generateEl(bo));
        // cmpProperty 由 MapstructUtils 按同名同类型直接拷贝；持久化时 TypeHandler 转 JSON
        boolean flag = chainMapper.insert(add) > 0;
        if (flag) {
            bo.setId(add.getId());
        }
        return flag;
    }

    /**
     * 修改链路草稿：EL 由组件树重新生成，版本/状态/模板标记不接受本接口修改。
     *
     * @param bo     链路业务对象
     * @param userId 当前用户 id（编辑归属校验：非作者拒改）
     * @return 是否修改成功
     */
    public Boolean update(DatabusChainBo bo, Long userId) {
        DatabusChain existing = chainMapper.selectById(bo.getId());
        if (existing == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        // 编辑归属校验（P1 看得见配套）：审核者可从审核详情打开他人链路画布查看，
        // 只读态由前端禁保存，保存的硬拦截在服务端——
        //   ① 系统资源（owner_type=SYSTEM）仅带 databus:market:review 权限的治理角色可改；
        //   ② 用户资源仅 create_by 指向的作者本人可改。
        if (OwnerTypeEnum.SYSTEM.getCode().equals(existing.getOwnerType())) {
            if (!StpUtil.hasPermission("databus:market:review")) {
                throw new ServiceException("系统链路仅治理角色可修改");
            }
        } else if (!Objects.equals(existing.getCreateBy(), userId)) {
            throw new ServiceException("只能修改自己创建的链路");
        }
        validateChainCodeUnique(bo);
        validateLogLevel(bo.getLogLevel());
        InputParamValidator.validateDefs(bo.getInputParams());
        DatabusChain update = MapstructUtils.convert(bo, DatabusChain.class);
        // 版本/状态不接受编辑接口修改（发布流转走独立接口）；
        // 模板标记同理——标记/取消走模板专用端点，通用编辑不触碰三字段（updateById 默认不更新 null）
        update.setVersion(null);
        update.setStatus(null);
        update.setIsTemplate(null);
        update.setElExpression(generateEl(bo));
        boolean flag = chainMapper.updateById(update) > 0;
        if (flag && bo.getUpdateBy() != null) {
            // 无登录态（AI 工具线程 / 内部调用）时 updateFill 会把 update_by 冲成 -1，
            // 调用方显式给出真实操作者时补写一次，保留审计可追溯。
            chainMapper.update(null, Wrappers.<DatabusChain>lambdaUpdate()
                .eq(DatabusChain::getId, bo.getId())
                .set(DatabusChain::getUpdateBy, bo.getUpdateBy()));
        }
        return flag;
    }

    /**
     * 批量删除：先做引用保护，删后按 chainCode 清 Rule-DB 残留规则并级联清安装血缘。
     *
     * @param ids 主键集合
     * @return 是否删除成功
     */
    public Boolean delete(Collection<Long> ids) {
        // 先查后删：删除后需按 chainCode 清理 Rule-DB 残留规则（未发布的链路 lf_chain 无记录，remove 幂等无害）
        List<DatabusChain> chains = chainMapper.selectByIds(ids);
        // 删前引用保护（decisions §十四-10）：被外部链路引用的链不能删，否则留悬空引用、
        // 引用方发布/执行时才报错，故障点离删除操作太远
        graphSupport.assertNotReferenced(chains);
        boolean flag = chainMapper.deleteByIds(ids) > 0;
        if (flag) {
            for (DatabusChain chain : chains) {
                ChainRuleDbPublisher.removeChainQuietly(chain.getChainCode());
            }
            cleanupInstallRecords(ids);
        }
        return flag;
    }

    /**
     * 由画布组件树生成 EL；组件树为空时返回 null（空画布不产出 EL）。
     *
     * @param cmpProperty 组件树
     * @return EL 表达式
     */
    public String generateEl(CmpProperty cmpProperty) {
        if (cmpProperty == null) {
            return null;
        }
        ELInfo elInfo = expressGenerator.generateEL(cmpProperty);
        return elInfo == null ? null : elInfo.getElStr();
    }

    /**
     * 编码在未删除记录中是否已存在（excludeId 为排除自身的主键，复制新链路传 null）。
     *
     * @param chainCode 链路编码
     * @param excludeId 排除的主键
     * @return 是否已存在
     */
    public boolean existsByChainCode(String chainCode, Long excludeId) {
        Long count = chainMapper.selectCount(Wrappers.<DatabusChain>lambdaQuery()
            .eq(DatabusChain::getChainCode, chainCode)
            .ne(excludeId != null, DatabusChain::getId, excludeId));
        return count != null && count > 0;
    }

    private String generateEl(DatabusChainBo bo) {
        return generateEl(bo.getCmpProperty());
    }

    /**
     * 校验链路编码在未删除记录中唯一
     */
    private void validateChainCodeUnique(DatabusChainBo bo) {
        Long currentId = bo.getId() == null ? -1L : bo.getId();
        if (existsByChainCode(bo.getChainCode(), currentId)) {
            throw new ServiceException("链路编码'" + bo.getChainCode() + "'已存在");
        }
    }

    /**
     * 校验 log_level 取值合法（OFF/BASIC/FULL）；空值跳过校验由 DB 默认值兜底。
     */
    private void validateLogLevel(String logLevel) {
        if (StringUtils.isBlank(logLevel)) {
            return;
        }
        if (!LogLevelEnum.isValid(logLevel)) {
            throw new ServiceException("执行记录档位'" + logLevel + "'非法（合法值：OFF/BASIC/FULL）");
        }
    }

    /**
     * 级联清安装血缘（2026-10-24）：本地链是市场物料的 FORK 安装副本时，直接删链要一并删
     * databus_install 记录并重算物料安装计数——否则「已安装」列表留悬空卡片，
     * 卡片「打开」按 localResourceId 开的是已删链，画布空白。
     * 与市场 uninstall 端点同口径，此处补「资源树直接删副本」这条绕过路径。
     */
    private void cleanupInstallRecords(Collection<Long> ids) {
        List<DatabusInstall> installs = installMapper.selectList(Wrappers.<DatabusInstall>lambdaQuery()
            .eq(DatabusInstall::getLocalResourceType, TYPE_CHAIN)
            .in(DatabusInstall::getLocalResourceId, ids));
        if (installs.isEmpty()) {
            return;
        }
        installMapper.deleteByIds(installs.stream().map(DatabusInstall::getId).toList());
        // 受影响市场物料重算安装计数（与 MarketInstallService.refreshCounts 同口径）
        List<Long> itemIds = installs.stream()
            .map(DatabusInstall::getMarketItemId).filter(Objects::nonNull).distinct().toList();
        for (Long itemId : itemIds) {
            Long count = installMapper.selectCount(Wrappers.<DatabusInstall>lambdaQuery()
                .eq(DatabusInstall::getMarketItemId, itemId));
            DatabusMarketItem update = new DatabusMarketItem();
            update.setId(itemId);
            update.setInstallCount(count == null ? 0 : count.intValue());
            marketItemMapper.updateById(update);
        }
    }

}
