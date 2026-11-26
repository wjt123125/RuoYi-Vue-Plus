package org.dromara.databus.service.support.component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.component.schema.enums.EditorKind;
import org.dromara.databus.domain.DatabusComponent;
import org.dromara.databus.domain.DatabusComponentVersion;
import org.dromara.databus.domain.bo.ScriptRollbackBo;
import org.dromara.databus.domain.bo.ScriptSaveBo;
import org.dromara.databus.domain.vo.DatabusComponentVersionVo;
import org.dromara.databus.domain.vo.ScriptRuntimeVo;
import org.dromara.databus.domain.vo.ScriptSaveResultVo;
import org.dromara.databus.mapper.DatabusComponentMapper;
import org.dromara.databus.mapper.DatabusComponentVersionMapper;
import org.dromara.databus.script.host.JavaSourceCompiler;
import org.dromara.databus.script.host.ScriptArtifact;
import org.dromara.databus.script.host.ScriptComponentRegistrar;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.dromara.databus.constant.ComponentConstants.SCRIPT_LANG_JAVA;

/**
 * 脚本宿主：保存即编译、回滚、版本历史、运行时健康。
 * <p>
 * 事务边界留在 {@link DatabusComponentServiceImpl}；编译/实例化/注册任一步失败由调用方事务整体回滚，
 * 现网继续跑旧版节点。
 *
 * @author databus
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class ComponentScriptSupport {

    private final DatabusComponentMapper componentMapper;

    private final DatabusComponentVersionMapper componentVersionMapper;

    private final JavaSourceCompiler scriptCompiler;

    private final ScriptComponentRegistrar scriptRegistrar;

    private final ComponentConnectorDepSupport depSupport;

    /**
     * 保存脚本正文：保存即编译。
     *
     * @param bo 脚本保存入参
     * @return 新版本号与物化契约
     */
    public ScriptSaveResultVo saveScript(ScriptSaveBo bo) {
        DatabusComponent row = requireComponent(bo.getId());
        String lang = StringUtils.isBlank(bo.getScriptLang()) ? SCRIPT_LANG_JAVA : bo.getScriptLang().trim();

        // 1. 编译 + 结构校验（失败抛 ScriptCompileException，事务尚未开启写操作，自然不落库）
        ScriptArtifact artifact = scriptCompiler.compile(bo.getScriptBody());
        validateDeclaredCode(artifact, row);

        // 2. 版本表追加 + 主表工件/契约列更新
        int nextVersion = StringUtils.isBlank(row.getScriptBody())
            ? 1 : (row.getVersion() == null ? 1 : row.getVersion() + 1);
        insertVersion(row.getId(), nextVersion, lang, bo.getScriptBody(), null);
        updateScriptColumns(row, lang, bo.getScriptBody(), nextVersion, artifact);

        // 3. 热替换 FlowBus（实例化/注入/注册失败抛异常 → 回滚上面全部 DB 写，
        //    现网继续跑旧版节点，做到「能保存即可运行」）
        scriptRegistrar.register(row, artifact);
        return buildSaveResult(row.getId(), nextVersion, lang, artifact);
    }

    /**
     * 一键回滚：取目标版本源码重走保存管线。
     *
     * @param bo 回滚入参
     * @return 新版本号与物化契约
     */
    public ScriptSaveResultVo rollbackScript(ScriptRollbackBo bo) {
        DatabusComponent row = requireComponent(bo.getId());
        DatabusComponentVersion target = componentVersionMapper.selectOne(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, bo.getId())
                .eq(DatabusComponentVersion::getVersionNo, bo.getVersionNo()));
        if (target == null) {
            throw new ServiceException("版本 v" + bo.getVersionNo() + " 不存在");
        }
        if (row.getVersion() != null && row.getVersion().equals(bo.getVersionNo())) {
            throw new ServiceException("v" + bo.getVersionNo() + " 即当前运行版本，无需回滚");
        }

        // 旧版本保存时已编译通过；若当前 classpath 下编译/注入失败，同样整体不落库
        ScriptArtifact artifact = scriptCompiler.compile(target.getScriptBody());
        validateDeclaredCode(artifact, row);

        DatabusComponentVersion latest = componentVersionMapper.selectOne(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, bo.getId())
                .orderByDesc(DatabusComponentVersion::getVersionNo)
                .last("limit 1"));
        int nextVersion = (latest == null ? 0 : latest.getVersionNo()) + 1;
        String lang = StringUtils.isBlank(target.getScriptLang()) ? SCRIPT_LANG_JAVA : target.getScriptLang();

        insertVersion(row.getId(), nextVersion, lang, target.getScriptBody(),
            "回滚自版本 v" + target.getVersionNo());
        updateScriptColumns(row, lang, target.getScriptBody(), nextVersion, artifact);
        scriptRegistrar.register(row, artifact);
        log.info("[databus-script] 组件 {} 回滚 v{} → 新版本 v{}",
            row.getComponentCode(), target.getVersionNo(), nextVersion);
        return buildSaveResult(row.getId(), nextVersion, lang, artifact);
    }

    /**
     * 查询组件脚本版本历史（版本号倒序）。
     *
     * @param componentId 组件主键
     * @return 版本列表
     */
    public List<DatabusComponentVersionVo> queryVersions(Long componentId) {
        requireComponent(componentId);
        return componentVersionMapper.selectVoList(
            Wrappers.<DatabusComponentVersion>lambdaQuery()
                .eq(DatabusComponentVersion::getComponentId, componentId)
                .orderByDesc(DatabusComponentVersion::getVersionNo));
    }

    /**
     * 运行时注册健康快照。
     *
     * @return 健康列表
     */
    public List<ScriptRuntimeVo> queryRuntimeHealth() {
        return scriptRegistrar.snapshot();
    }

    /**
     * 按主键加载组件行，不存在即抛业务异常。
     */
    public DatabusComponent requireComponent(Long id) {
        DatabusComponent row = componentMapper.selectById(id);
        if (row == null) {
            throw new ServiceException("组件不存在或已删除");
        }
        return row;
    }

    /**
     * 脚本 @DatabusCmp(code) 允许留空（运行 id 一律以组件表编码为准）；显式声明时必须与表编码一致，
     * 防止作者复制源码后编码漂移导致 FlowBus 注册到错误 id。
     */
    private void validateDeclaredCode(ScriptArtifact artifact, DatabusComponent row) {
        if (StringUtils.isNotBlank(artifact.declaredCode())
            && !artifact.declaredCode().equals(row.getComponentCode())) {
            throw new ServiceException("@DatabusCmp(code=\"" + artifact.declaredCode()
                + "\") 与组件编码 " + row.getComponentCode()
                + " 不一致（脚本件 code 建议留空，按组件编码注册）");
        }
    }

    private void insertVersion(Long componentId, int versionNo, String lang, String body, String remark) {
        DatabusComponentVersion version = new DatabusComponentVersion();
        version.setComponentId(componentId);
        version.setVersionNo(versionNo);
        version.setScriptLang(lang);
        version.setScriptBody(body);
        version.setRemark(remark);
        componentVersionMapper.insert(version);
    }

    /**
     * 更新主表工件三列 + 反射物化的契约缓存列；同步内存 row 供注册器读 code/version。
     * data_example 仅在非空时更新（无法以 null 清空，作者改示例须给新值）。
     */
    private void updateScriptColumns(DatabusComponent row, String lang, String body, int nextVersion,
                                     ScriptArtifact artifact) {
        DatabusComponent update = new DatabusComponent();
        update.setId(row.getId());
        update.setScriptLang(lang);
        update.setScriptBody(body);
        update.setVersion(nextVersion);
        update.setNodeType(artifact.nodeType().name());
        update.setEditor(EditorKind.FORM.jsonValue());
        update.setParamSchema(artifact.paramSchemaJson());
        if (artifact.dataExample() != null) {
            update.setDataExample(artifact.dataExample());
        }
        componentMapper.updateById(update);
        // 写入点 1（material-market §15.12.7）：一处覆盖脚本保存与脚本回滚两个动作（都调本方法）。
        // artifact.fields() 已是 List<PropSchema>，直接取用，不必回解析刚写进去的 param_schema JSON
        depSupport.writeConnectorDeps(row.getComponentCode(), artifact.fields());

        row.setScriptLang(lang);
        row.setScriptBody(body);
        row.setVersion(nextVersion);
    }

    private ScriptSaveResultVo buildSaveResult(Long componentId, int version, String lang,
                                               ScriptArtifact artifact) {
        ScriptSaveResultVo result = new ScriptSaveResultVo();
        result.setSuccess(Boolean.TRUE);
        result.setComponentId(componentId);
        result.setVersion(version);
        result.setScriptLang(lang);
        result.setNodeType(artifact.nodeType().name());
        result.setEditor(EditorKind.FORM.jsonValue());
        result.setParamSchema(artifact.paramSchemaJson());
        result.setDataExample(artifact.dataExample());
        result.setFieldCount(artifact.fields().size());
        return result;
    }

}
