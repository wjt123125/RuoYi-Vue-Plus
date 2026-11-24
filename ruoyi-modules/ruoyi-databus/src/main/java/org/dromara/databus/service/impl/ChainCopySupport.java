package org.dromara.databus.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.ChainCopyBo;
import org.dromara.databus.domain.bo.TemplateMarkBo;
import org.dromara.databus.domain.vo.CopySuggestionVo;
import org.dromara.databus.enums.ChainStatusEnum;
import org.dromara.databus.enums.OwnerTypeEnum;
import org.dromara.databus.mapper.DatabusChainMapper;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

import static org.dromara.databus.constant.ChainConstants.CHAIN_CODE_MAX_LEN;
import static org.dromara.databus.constant.ChainConstants.CHAIN_NAME_MAX_LEN;
import static org.dromara.databus.constant.ChainConstants.COPY_CODE_SEQ_MAX;
import static org.dromara.databus.constant.ChainConstants.COPY_CODE_SEQ_START;
import static org.dromara.databus.constant.ChainConstants.COPY_NAME_SUFFIX;
import static org.dromara.databus.constant.ChainConstants.DRAFT_VERSION;
import static org.dromara.databus.constant.ChainConstants.TEMPLATE_DEFAULT_SORT;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_NO;
import static org.dromara.databus.constant.CommonConstants.TEMPLATE_FLAG_YES;

/**
 * 链路复制与精选模板标记：复制出新草稿、复制建议值、模板的设/取消。
 * <p>
 * 副本一律普通链路（剥离模板身份）；草稿不推 Rule-DB，EL 由组件树按新增口径重新生成。
 * 只被 {@link DatabusChainServiceImpl} 调用。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Component
public class ChainCopySupport {

    /**
     * 编码结尾「_数字」：建议值先剥离源编码既有 _N 尾缀，避免 xxx_2_2
     */
    private static final Pattern CODE_TRAILING_SEQ = Pattern.compile("_\\d+$");

    private final DatabusChainMapper chainMapper;

    private final ChainWriteSupport writeSupport;

    /**
     * 复制链路：以源链路的画布与组件配置生成一条全新草稿。
     *
     * @param id 源链路主键
     * @param bo 副本名称/编码
     * @return 新链路主键（供「使用模板」复制后直跳编辑器）
     */
    public Long copy(Long id, ChainCopyBo bo) {
        DatabusChain source = chainMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        String chainCode = StringUtils.trim(bo.getChainCode());
        if (writeSupport.existsByChainCode(chainCode, null)) {
            throw new ServiceException("链路编码'" + chainCode + "'已存在");
        }
        // 全新实体：不复制 id/审计字段，插入时由 MetaObjectHandler 自动填充
        DatabusChain add = new DatabusChain();
        add.setVersion(DRAFT_VERSION);
        add.setStatus(ChainStatusEnum.DRAFT.getCode());
        add.setChainName(StringUtils.trim(bo.getChainName()));
        add.setChainCode(chainCode);
        // 画布/组件树/记录档位/入参登记原样复制；组件树中引用的连接器仅复制 connectionId（共享连接器，不复制其本身）
        add.setCanvasData(source.getCanvasData());
        add.setCmpProperty(source.getCmpProperty());
        add.setLogLevel(source.getLogLevel());
        add.setInputParams(source.getInputParams());
        add.setRemark(source.getRemark());
        // 有意剥离模板身份：副本一律普通链路（说明/排序不继承，new 实体为 null，标记显式置 0）
        add.setIsTemplate(TEMPLATE_FLAG_NO);
        add.setOwnerType(OwnerTypeEnum.USER.getCode());
        // 草稿不推 Rule-DB；与新增草稿同口径，EL 由组件树实时生成而非复制源 EL 文本
        add.setElExpression(writeSupport.generateEl(source.getCmpProperty()));
        chainMapper.insert(add);
        // 雪花 id 回填，供「使用模板」复制后直跳编辑器
        return add.getId();
    }

    /**
     * 复制建议值：名称建议「源名称+副本」；编码在源编码（剥离既有 _N 尾缀）基础上
     * 从 _2 起查库取首个未占用值，供复制弹窗预填。
     *
     * @param id 源链路主键
     * @return 名称/编码建议
     */
    public CopySuggestionVo getCopySuggestion(Long id) {
        DatabusChain source = chainMapper.selectById(id);
        if (source == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        CopySuggestionVo vo = new CopySuggestionVo();
        vo.setChainName(buildCopyName(source.getChainName()));
        vo.setChainCode(buildSuggestedCode(source.getChainCode()));
        return vo;
    }

    /**
     * 设为精选模板：写模板标记 + 说明 + 排序。
     *
     * @param id 链路主键
     * @param bo 模板设置（说明必填、排序可空）
     * @return 是否设置成功
     */
    public Boolean markAsTemplate(Long id, TemplateMarkBo bo) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (ChainStatusEnum.ENABLED.getCode().equals(chain.getStatus())) {
            throw new ServiceException("当前链路已发布，请先下线后再设为精选模板");
        }
        Integer sort = bo.getTemplateSort() == null ? TEMPLATE_DEFAULT_SORT : bo.getTemplateSort();
        DatabusChain update = new DatabusChain();
        update.setId(id);
        update.setIsTemplate(TEMPLATE_FLAG_YES);
        update.setTemplateDesc(bo.getTemplateDesc());
        update.setTemplateSort(sort);
        // 模板恒草稿：已下线链路转模板时回归草稿态（下线时 Rule-DB 规则已移除，无残留）
        update.setStatus(ChainStatusEnum.DRAFT.getCode());
        return chainMapper.updateById(update) > 0;
    }

    /**
     * 取消精选模板：清除标记/说明/排序，链路回到普通草稿（不改变 status）。
     *
     * @param id 链路主键
     * @return 是否取消成功
     */
    public Boolean unmarkTemplate(Long id) {
        DatabusChain chain = chainMapper.selectById(id);
        if (chain == null) {
            throw new ServiceException("链路不存在或已删除");
        }
        if (!TEMPLATE_FLAG_YES.equals(chain.getIsTemplate())) {
            throw new ServiceException("该链路不是精选模板");
        }
        // 清空说明/排序需显式 set null：updateById 默认 NOT_NULL 策略不更新 null，故走 UpdateWrapper；
        // 不触碰 status——模板期间恒为草稿，取消后即普通草稿
        return chainMapper.update(null, Wrappers.<DatabusChain>lambdaUpdate()
            .set(DatabusChain::getIsTemplate, TEMPLATE_FLAG_NO)
            .set(DatabusChain::getTemplateDesc, null)
            .set(DatabusChain::getTemplateSort, TEMPLATE_DEFAULT_SORT)
            .eq(DatabusChain::getId, id)) > 0;
    }

    /**
     * 副本名称：源名称后加"副本"，长度超 100 截断
     */
    private String buildCopyName(String sourceName) {
        String name = sourceName + COPY_NAME_SUFFIX;
        return name.length() > CHAIN_NAME_MAX_LEN ? name.substring(0, CHAIN_NAME_MAX_LEN) : name;
    }

    /**
     * 副本编码建议值：源编码先剥离结尾既有 _N 尾缀（避免副本的副本变 xxx_2_2），
     * 再从 _2 起查库取首个未占用值。预留序号段长度保证总长不超 100。
     */
    private String buildSuggestedCode(String sourceCode) {
        String base = CODE_TRAILING_SEQ.matcher(sourceCode).replaceFirst("");
        if (StringUtils.isBlank(base)) {
            // 极端情况：源编码本身就是 "_2" 之类，剥离后为空，回退用源编码
            base = sourceCode;
        }
        // 预留最长序号段 "_9999"
        int suffixReserve = 1 + String.valueOf(COPY_CODE_SEQ_MAX).length();
        int maxBaseLen = CHAIN_CODE_MAX_LEN - suffixReserve;
        if (base.length() > maxBaseLen) {
            base = base.substring(0, maxBaseLen);
        }
        for (int seq = COPY_CODE_SEQ_START; seq <= COPY_CODE_SEQ_MAX; seq++) {
            String candidate = base + "_" + seq;
            if (!writeSupport.existsByChainCode(candidate, null)) {
                return candidate;
            }
        }
        throw new ServiceException("复制失败：无法生成唯一链路编码，请手工指定");
    }

}
