package org.dromara.databus.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.DatabusChain;
import org.dromara.databus.domain.bo.ChainCopyBo;
import org.dromara.databus.domain.bo.DatabusChainBo;
import org.dromara.databus.domain.bo.TemplateMarkBo;
import org.dromara.databus.domain.vo.ChainPublishResultVo;
import org.dromara.databus.domain.vo.ChainStatsVo;
import org.dromara.databus.domain.vo.CopySuggestionVo;
import org.dromara.databus.domain.vo.DatabusChainVo;
import org.dromara.databus.el.bean.CmpProperty;
import org.dromara.databus.service.IDatabusChainService;
import org.dromara.databus.service.support.chain.ChainCopySupport;
import org.dromara.databus.service.support.chain.ChainPublishSupport;
import org.dromara.databus.service.support.chain.ChainQuerySupport;
import org.dromara.databus.service.support.chain.ChainRefGraphSupport;
import org.dromara.databus.service.support.chain.ChainWriteSupport;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 链路定义 Service 实现。
 * <p>
 * 本类只保留接口入口与事务边界：读查询、写定义、引用图、发布下线、复制模板分别委托给
 * {@link ChainQuerySupport}、{@link ChainWriteSupport}、{@link ChainRefGraphSupport}、
 * {@link ChainPublishSupport}、{@link ChainCopySupport}。
 * <p>
 * 发布/下线与 Rule-DB 的联动顺序（发布先推规则后改状态、下线先改状态后移除规则）见
 * {@link ChainPublishSupport}；删除时的引用保护与安装血缘清理见 {@link ChainWriteSupport}。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusChainServiceImpl implements IDatabusChainService {

    private final ChainQuerySupport querySupport;

    private final ChainWriteSupport writeSupport;

    private final ChainRefGraphSupport graphSupport;

    private final ChainPublishSupport publishSupport;

    private final ChainCopySupport copySupport;

    @Override
    public DatabusChainVo queryById(Long id) {
        return querySupport.queryById(id);
    }

    @Override
    public PageResult<DatabusChainVo> queryPageList(DatabusChainBo bo, PageQuery pageQuery, Long userId) {
        return querySupport.queryPageList(bo, pageQuery, userId);
    }

    @Override
    public ChainStatsVo countByStatus() {
        return querySupport.countByStatus();
    }

    @Override
    public Boolean insertByBo(DatabusChainBo bo) {
        return writeSupport.insert(bo);
    }

    @Override
    public Boolean updateByBo(DatabusChainBo bo, Long userId) {
        return writeSupport.update(bo, userId);
    }

    @Override
    public Boolean deleteByIds(Collection<Long> ids) {
        return writeSupport.delete(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChainPublishResultVo publish(Long id, Long userId) {
        return publishSupport.publish(id, userId);
    }

    @Override
    public Boolean offline(Long id) {
        return publishSupport.offline(id);
    }

    @Override
    public Long copy(Long id, ChainCopyBo bo) {
        return copySupport.copy(id, bo);
    }

    @Override
    public CopySuggestionVo getCopySuggestion(Long id) {
        return copySupport.getCopySuggestion(id);
    }

    @Override
    public Boolean markAsTemplate(Long id, TemplateMarkBo bo) {
        return copySupport.markAsTemplate(id, bo);
    }

    @Override
    public Boolean unmarkTemplate(Long id) {
        return copySupport.unmarkTemplate(id);
    }

    @Override
    public LinkedHashMap<String, DatabusChain> collectChainClosure(String rootChainCode) {
        return graphSupport.collectClosure(rootChainCode);
    }

    @Override
    public List<String> collectChainRefs(CmpProperty root) {
        return graphSupport.collectRefs(root);
    }

}
