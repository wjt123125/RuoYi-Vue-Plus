package org.dromara.databus.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.databus.domain.bo.DatabusComponentBo;
import org.dromara.databus.domain.bo.ScriptDraftBo;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.ComponentDomainVo;
import org.dromara.databus.domain.vo.ComponentGroupVo;
import org.dromara.databus.domain.vo.ComponentOptionsVo;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.DatabusComponentVo;
import org.dromara.databus.domain.vo.ScriptDraftResultVo;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;
import org.dromara.databus.service.IDatabusComponentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * 组件元信息 Service 实现。
 * <p>
 * 本类只保留接口入口与事务边界：读查询委托 {@link ComponentQuerySupport}，定义写入委托
 * {@link ComponentWriteSupport}，脚本宿主委托 {@link ComponentScriptSupport}，脚本草稿与画布预览委托
 * {@link ComponentDraftSupport}；连接器依赖行的派生写入统一收在 {@link ComponentDepSupport}。
 *
 * @author databus
 */
@RequiredArgsConstructor
@Service
public class DatabusComponentServiceImpl implements IDatabusComponentService {

    private final ComponentQuerySupport querySupport;

    private final ComponentWriteSupport writeSupport;

    private final ComponentScriptSupport scriptSupport;

    private final ComponentDraftSupport draftSupport;

    @Override
    public DatabusComponentVo queryById(Long id) {
        return querySupport.queryById(id);
    }

    @Override
    public PageResult<DatabusComponentVo> queryPageList(DatabusComponentBo bo, PageQuery pageQuery) {
        return querySupport.queryPageList(bo, pageQuery);
    }

    @Override
    public ComponentOptionsVo queryOptions() {
        return querySupport.queryOptions();
    }

    @Override
    public List<ComponentGroupVo> queryGroups() {
        return querySupport.queryGroups();
    }

    @Override
    public List<ComponentDomainVo> queryDomains() {
        return querySupport.queryDomains();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean insertByBo(DatabusComponentBo bo) {
        return writeSupport.insert(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(DatabusComponentBo bo) {
        return writeSupport.update(bo);
    }

    @Override
    public Boolean updateStatus(Long id, String status) {
        return writeSupport.updateStatus(id, status);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        return writeSupport.delete(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptSaveResultVo saveScript(ScriptSaveBo bo) {
        return scriptSupport.saveScript(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptSaveResultVo rollbackScript(ScriptRollbackBo bo) {
        return scriptSupport.rollbackScript(bo);
    }

    @Override
    public List<DatabusComponentVersionVo> queryVersions(Long componentId) {
        return scriptSupport.queryVersions(componentId);
    }

    @Override
    public List<ScriptRuntimeVo> queryRuntimeHealth() {
        return scriptSupport.queryRuntimeHealth();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptDraftResultVo saveScriptDraft(ScriptDraftBo bo) {
        return draftSupport.saveScriptDraft(bo);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ScriptSaveResultVo adoptScriptDraft(Long id) {
        return draftSupport.adoptScriptDraft(id);
    }

    @Override
    public String registerDraftForPreview(Long draftId) {
        return draftSupport.registerDraftForPreview(draftId);
    }

    @Override
    public void unregisterDraftForPreview(String nodeId) {
        draftSupport.unregisterDraftForPreview(nodeId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean rejectScriptDraft(Long id) {
        return draftSupport.rejectScriptDraft(id);
    }

}
